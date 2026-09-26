package com.dewijones92.totum.playback

import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

internal fun interface SpeechWorker {
    fun submit(task: Runnable)

    companion object {
        val INLINE: SpeechWorker = SpeechWorker { it.run() }

        val BACKGROUND: SpeechWorker by lazy {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "speech-detector").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
            SpeechWorker(executor::execute)
        }
    }
}

internal class SpeechTrack(
    private val model: SpeechModel,
    inputRate: Int,
    private val worker: SpeechWorker = SpeechWorker.INLINE,
) {

    private var block = FloatArray(BLOCK)
    private var blockFill = 0

    @Volatile
    private var decidedUpTo = 0L

    @Volatile
    private var retired = false

    private var warmingUp = 0

    private val step = inputRate.toDouble() / SpeechModel.SAMPLE_RATE
    private val taps = lowPass(inputRate)
    private val history = FloatArray(taps.size * 2)
    private var historyAt = 0
    private var previous = 0f
    private var current = 0f
    private var filteredFrames = 0L
    private var nextOutputAt = 0.0
    private val chunk = FloatArray(SpeechModel.CHUNK)
    private var chunkFill = 0
    private var chunkIndex = 0L
    private var speaking = false

    @Volatile
    private var chunksDone = 0L
    private val verdicts = ByteArray(VERDICT_RING)
    private val chunkFrames = SpeechModel.CHUNK * step
    private val delayFrames = (taps.size - 1) / 2

    @Volatile
    var chunksSpeech: Long = 0L
        private set

    @Volatile
    var chunksDropped: Long = 0L
        private set

    @Volatile
    private var modelNanos = 0L

    @Volatile
    private var chunksModelled = 0L

    val microsPerChunk: Long get() = if (chunksModelled == 0L) 0L else modelNanos / chunksModelled / NANOS_PER_MICRO

    val chunksHeard: Long get() = chunksDone

    fun push(sample: Float) {
        block[blockFill++] = sample
        if (blockFill == BLOCK) handOver(finishing = false)
    }

    fun finish() {
        handOver(finishing = true)
    }

    fun isSpeech(frame: Long): Boolean? {
        if (frame > decidedUpTo) decidedUpTo = frame
        val centre = ((frame + delayFrames) / chunkFrames).toLong()
        val last = centre + AFTER_CHUNKS
        if (last >= chunksDone) return null
        var known = true
        for (index in maxOf(0L, centre - BEFORE_CHUNKS)..last) {
            if (chunksDone - index > VERDICT_RING) return null
            when (verdicts[(index % VERDICT_RING).toInt()]) {
                SPEECH -> return true
                UNKNOWN -> known = false
            }
        }
        return if (known) false else null
    }

    private fun handOver(finishing: Boolean) {
        val samples = block
        val count = blockFill
        block = FloatArray(BLOCK)
        blockFill = 0
        worker.submit {
            if (!retired) {
                for (k in 0 until count) resample(samples[k])
                if (finishing) while (chunkFill != 0) emit(0f)
            }
        }
    }

    fun retire() {
        retired = true
    }

    private fun resample(sample: Float) {
        history[historyAt] = sample
        history[historyAt + taps.size] = sample
        historyAt = (historyAt + 1) % taps.size
        var filtered = 0f
        for (k in taps.indices) filtered += taps[k] * history[historyAt + k]
        previous = current
        current = filtered
        filteredFrames++
        val newest = (filteredFrames - 1).toDouble()
        while (nextOutputAt <= newest) {
            val fraction = (nextOutputAt - (newest - 1)).toFloat().coerceIn(0f, 1f)
            emit(previous + (current - previous) * fraction)
            nextOutputAt += step
        }
    }

    private fun emit(value: Float) {
        chunk[chunkFill++] = value
        if (chunkFill < SpeechModel.CHUNK) return
        chunkFill = 0
        val index = chunkIndex++
        val neededUntil = ((index + BEFORE_CHUNKS + 1) * chunkFrames).toLong() - delayFrames
        verdicts[(index % VERDICT_RING).toInt()] = if (neededUntil < decidedUpTo) {
            chunksDropped++
            warmingUp = WARM_UP_AFTER_GAP
            UNKNOWN
        } else {
            judge()
        }
        chunksDone = index + 1
    }

    private fun judge(): Byte {
        val started = System.nanoTime()
        val probability = model.probability(chunk)
        modelNanos += System.nanoTime() - started
        chunksModelled++
        speaking = if (speaking) probability >= STOP else probability >= START
        if (speaking) chunksSpeech++
        val trusted = warmingUp == 0
        if (!trusted) warmingUp--
        return when {
            speaking -> SPEECH
            trusted -> NOT_SPEECH
            else -> UNKNOWN
        }
    }

    private fun lowPass(rate: Int): FloatArray {
        if (rate <= SpeechModel.SAMPLE_RATE) return floatArrayOf(1f)
        val cutoff = CUTOFF_HZ / rate
        val middle = (LOW_PASS_TAPS - 1) / 2.0
        val raw = DoubleArray(LOW_PASS_TAPS) { k ->
            val x = k - middle
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            sinc * (HAMMING_A - HAMMING_B * cos(2 * PI * k / (LOW_PASS_TAPS - 1)))
        }
        val sum = raw.sum()
        return FloatArray(LOW_PASS_TAPS) { (raw[it] / sum).toFloat() }
    }

    internal companion object {
        const val START = 0.5f
        const val STOP = 0.35f
        const val BEFORE_CHUNKS = 1
        const val AFTER_CHUNKS = 1
        const val BLOCK = 2_048
        const val WARM_UP_AFTER_GAP = 6
        private const val VERDICT_RING = 256
        private const val CUTOFF_HZ = 7_000.0
        private const val LOW_PASS_TAPS = 15
        private const val HAMMING_A = 0.54
        private const val HAMMING_B = 0.46
        private const val NANOS_PER_MICRO = 1_000L
        private const val UNKNOWN: Byte = 0
        private const val SPEECH: Byte = 1
        private const val NOT_SPEECH: Byte = 2
    }
}
