package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.LoudnessBoost
import com.dewijones92.totum.playback.SilenceCutter
import com.dewijones92.totum.playback.SpeechModel
import com.dewijones92.totum.playback.SpeechTrack
import com.dewijones92.totum.playback.SpeechWeights
import com.dewijones92.totum.playback.VolumeBoost
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

internal object RealSpeech {

    const val RATE = 44_100
    const val MILLIS = 1_000L
    private const val WINDOW_MS = 10
    private const val ACTIVE_RMS = 150.0
    private const val FULL_SCALE = 32_768.0
    private const val DECIBELS = 20.0
    private const val DECIBELS_PER_POWER = 10.0
    private const val PERCENT = 100

    val clip: ShortArray by lazy { decode(File("src/main/res/raw/silence_test_clip.ogg")) }

    val speech: BooleanArray by lazy { activeIn(clip) }

    val pauses: BooleanArray by lazy { pausesIn(speech) }

    fun decode(file: File): ShortArray {
        check(file.isFile) { "missing fixture ${file.absolutePath}" }
        val errors = File.createTempFile("ffmpeg", ".log").apply { deleteOnExit() }
        val process: Process = try {
            ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-ac", "1", "-ar", "$RATE", "-f", "s16le", "-")
                .redirectError(errors)
                .start()
        } catch (missing: java.io.IOException) {
            throw IllegalStateException("these tests decode real audio with ffmpeg; install it", missing)
        }
        val bytes = process.inputStream.readBytes()
        check(
            process.waitFor() == 0 && bytes.isNotEmpty()
        ) { "ffmpeg could not decode ${file.path}: ${errors.readText()}" }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(shorts.remaining()).also { shorts.get(it) }
    }

    fun gained(samples: ShortArray, db: Double, hissSigma: Double = 0.0, seed: Long = 1): ShortArray {
        val gain = 10.0.pow(db / DECIBELS)
        val random = Random(seed)
        return ShortArray(samples.size) { clamp(samples[it] * gain + random.nextGaussian() * hissSigma) }
    }

    fun burst(ms: Int, sigma: Double, seed: Long = 2): ShortArray {
        val random = Random(seed)
        return ShortArray(framesIn(ms)) { clamp(random.nextGaussian() * sigma) }
    }

    fun framesIn(ms: Int): Int = (ms.toLong() * RATE / MILLIS).toInt()

    fun millisOf(frames: Int): Long = frames * MILLIS / RATE

    fun activeIn(samples: ShortArray): BooleanArray {
        val rms = rms10(samples)
        return BooleanArray(samples.size) { rms[it] > ACTIVE_RMS }
    }

    fun pausesIn(active: BooleanArray): BooleanArray {
        val minimum = framesIn(SilenceCutter.MIN_SILENCE_MS)
        val pause = BooleanArray(active.size)
        var start = 0
        while (start < active.size) {
            if (active[start]) {
                start++
                continue
            }
            var end = start
            while (end < active.size && !active[end]) end++
            if (end - start >= minimum) for (k in start until end) pause[k] = true
            start = end
        }
        return pause
    }

    fun rms10(samples: ShortArray): DoubleArray {
        val window = framesIn(WINDOW_MS)
        val out = DoubleArray(samples.size)
        var sum = 0.0
        for (i in samples.indices) {
            sum += samples[i].toDouble() * samples[i]
            if (i >= window) sum -= samples[i - window].toDouble() * samples[i - window]
            out[maxOf(0, i - window / 2)] = sqrt(maxOf(0.0, sum) / window)
        }
        return out
    }

    val speechWeights: SpeechWeights by lazy {
        File("src/main/res/raw/silero_vad.bin").inputStream().use(SpeechWeights::read)
    }

    fun smartCutter(): SilenceCutter = SilenceCutter(RATE, 1, speech = SpeechTrack(SpeechModel(speechWeights), RATE))

    fun removedBy(samples: ShortArray, cutter: SilenceCutter = SilenceCutter(RATE, 1)): BooleanArray {
        val removed = BooleanArray(samples.size)
        val pad = SilenceCutter.framesIn(SilenceCutter.PAD_MS, RATE)
        val minimum = SilenceCutter.framesIn(SilenceCutter.MIN_SILENCE_MS, RATE)
        val delay = cutter.lookaheadFrames
        val one = ShortArray(1)
        fun mark(at: Int, count: Int) {
            if (count == 1) {
                if (at - pad in removed.indices) removed[at - pad] = true
            } else if (count > 1) {
                for (k in at - minimum + 1 + pad..at - pad) if (k in removed.indices) removed[k] = true
            }
        }
        for (i in 0 until samples.size + delay) {
            one[0] = if (i < samples.size) samples[i] else 0
            val before = cutter.skippedFrames
            cutter.process(one, 1)
            mark(i - delay, (cutter.skippedFrames - before).toInt())
        }
        return removed
    }

    fun judge(removed: BooleanArray, truth: ShortArray = clip): Judgement {
        var pauseRemoved = 0
        var lost = 0.0
        var total = 0.0
        for (k in 0 until minOf(removed.size, truth.size, speech.size)) {
            val energy = truth[k].toDouble() * truth[k]
            if (speech[k]) total += energy
            if (!removed[k]) continue
            if (pauses[k]) pauseRemoved++
            if (speech[k]) lost += energy
        }
        return Judgement(
            pausePercent = pauseRemoved * PERCENT / pauses.count { it }.coerceAtLeast(1),
            speechEnergyLost = lost / total.coerceAtLeast(1.0),
            speechFramesLostMs = millisOf(
                (0 until minOf(removed.size, speech.size)).count { removed[it] && speech[it] }
            ),
        )
    }

    fun cleanness(
        input: ShortArray,
        output: ShortArray,
        from: Int = 0,
        to: Int = minOf(
            input.size,
            output.size
        )
    ): Double {
        val window = framesIn(WINDOW_MS)
        var signal = 0.0
        var residual = 0.0
        var at = from
        while (at + window <= to) {
            var cross = 0.0
            var power = 0.0
            for (k in at until at + window) {
                cross += input[k].toDouble() * output[k]
                power += input[k].toDouble() * input[k]
            }
            val gain = if (power > 0) cross / power else 0.0
            for (k in at until at + window) {
                val error = output[k] - gain * input[k]
                residual += error * error
                signal += output[k].toDouble() * output[k]
            }
            at += window
        }
        return DECIBELS_PER_POWER * log10(signal / maxOf(residual, 1e-9))
    }

    fun cut(samples: ShortArray, channels: Int = 1, chunkFrames: (Int) -> Int = { 4096 }): Cut =
        cutWith(samples, SilenceCutter(RATE, channels), channels, chunkFrames)

    fun cutWith(
        samples: ShortArray,
        cutter: SilenceCutter,
        channels: Int = 1,
        chunkFrames: (Int) -> Int = { 4096 },
    ): Cut {
        val out = ArrayList<Short>(samples.size)
        var at = 0
        var call = 0
        while (at < samples.size) {
            val frames = minOf(chunkFrames(call++).coerceAtLeast(1), (samples.size - at) / channels)
            val chunk = samples.copyOfRange(at, at + frames * channels)
            val result = cutter.process(chunk, frames)
            for (k in 0 until cutter.outputSamples) out += result[k]
            at += frames * channels
        }
        val tail = cutter.endOfStream()
        for (k in 0 until cutter.outputSamples) out += tail[k]
        return Cut(out.toShortArray(), cutter.skippedFrames, cutter.gapsCut)
    }

    fun boosted(samples: ShortArray, chunk: Int = 4096): Boosted {
        val boost = LoudnessBoost(RATE).apply { level = VolumeBoost.AUTO }
        val out = ShortArray(samples.size)
        var written = 0
        var at = 0
        while (at < samples.size) {
            val count = minOf(chunk, samples.size - at)
            val result = boost.process(samples.copyOfRange(at, at + count), count)
            result.copyInto(out, written, 0, boost.outputSamples)
            written += boost.outputSamples
            at += count
        }
        val tail = boost.drain()
        tail.copyInto(out, written, 0, minOf(boost.outputSamples, out.size - written))
        return Boosted(out, boost.clippedSamples, boost.deepestLimit, boost.currentGain)
    }

    fun decibels(value: Double): Double = DECIBELS * log10(maxOf(value, 1e-9) / FULL_SCALE)

    fun medianActiveDb(samples: ShortArray, active: BooleanArray): Double {
        val rms = rms10(samples)
        val levels = rms.indices.filter { active[it] }.map { rms[it] }.sorted()
        return decibels(levels[levels.size / 2])
    }

    fun peak(samples: ShortArray): Int = samples.maxOf { abs(it.toInt()) }

    private fun clamp(value: Double): Short =
        value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

    class Cut(val output: ShortArray, val skippedFrames: Long, val gaps: Long)

    data class Judgement(val pausePercent: Int, val speechEnergyLost: Double, val speechFramesLostMs: Long)

    class Boosted(val output: ShortArray, val clipped: Long, val deepestLimit: Float, val finalGain: Float)
}
