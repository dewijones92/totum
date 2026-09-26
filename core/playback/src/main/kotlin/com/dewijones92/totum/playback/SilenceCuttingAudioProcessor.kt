package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class SilenceCuttingAudioProcessor : BaseAudioProcessor() {

    var enabled: Boolean = false

    private var cutter: SilenceCutter? = null
    private var samples = ShortArray(0)
    private var sampleRate = 0
    private val report = SilenceReport()

    val skippedFrames: Long get() = cutter?.skippedFrames ?: 0L

    val gapsCut: Long get() = cutter?.gapsCut ?: 0L

    val heard: HeardCuts? get() = cutter?.heard

    var flushes: Long = 0L
        private set

    private var previousSkippedBy: (Long) -> Long = NOTHING_CUT

    fun takePreviousSkippedBy(): (Long) -> Long = previousSkippedBy.also { previousSkippedBy = NOTHING_CUT }

    var relearnLevels = false

    @Volatile
    var mode: SilenceMode = SilenceMode.DEFAULT
        set(value) {
            if (field != value) Diag.log("silence", "mode -> $value")
            field = value
        }

    var speechWeights: () -> SpeechWeights? = { null }

    private var trackedMode: SilenceMode? = null

    private var saidModelMissing = false

    val cutLevel: Int get() = cutter?.cutLevel ?: SilenceCutter.THRESHOLD

    fun framesToUs(frames: Long): Long = if (sampleRate <= 0) 0L else frames * MICROS_PER_SECOND / sampleRate

    fun usToFrames(us: Long): Long = if (sampleRate <= 0) 0L else us * sampleRate / MICROS_PER_SECOND

    override fun isActive(): Boolean = super.isActive() && enabled

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            Diag.warn("silence", "encoding ${inputAudioFormat.encoding} is not 16-bit PCM; not cutting silence")
            return AudioProcessor.AudioFormat.NOT_SET
        }
        if (inputAudioFormat.sampleRate == Format.NO_VALUE) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val previous = cutter
        val previousRate = sampleRate
        previous?.let(report::banked)
        if (previous != null && previous.outputFrames + previous.skippedFrames > 0) {
            previousSkippedBy = skippedByOf(previous, sampleRate)
        }
        flushes++
        val carry = previous != null && previousRate == inputAudioFormat.sampleRate && !relearnLevels
        cutter = if (isActive) {
            sampleRate = inputAudioFormat.sampleRate
            report.sampleRate = sampleRate
            Diag.log(
                "silence",
                "cutting pauses over ${SilenceCutter.MIN_SILENCE_MS}ms to ${2 * SilenceCutter.PAD_MS}ms " +
                    "(rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}, " +
                    "cut level an eighth of the speech level, at most ${SilenceCutter.THRESHOLD}, " +
                    "judged ${SilenceCutter.LOOKAHEAD_MS}ms ahead; " +
                    "${if (carry) "keeping level ${previous?.cutLevel}" else "learning the level afresh"})",
            )
            val blockFrames = SilenceCutter.framesIn(SilenceCutter.BLOCK_MS, inputAudioFormat.sampleRate)
            val carried = previous?.levels?.takeIf { carry }
            relearnLevels = false
            val levels = carried ?: CutLevel(blockFrames.coerceAtLeast(1))
            SilenceCutter(inputAudioFormat.sampleRate, inputAudioFormat.channelCount.coerceAtLeast(1), levels)
                .also { trackedMode = null }
        } else {
            null
        }
    }

    private val followMode: (SilenceCutter) -> Unit = { active ->
        val wanted = mode
        val weights = if (wanted == SilenceMode.SMART) speechWeights() else null
        when {
            trackedMode == wanted -> Unit
            wanted == SilenceMode.SMART && weights == null -> {
                if (!saidModelMissing) {
                    Diag.log(
                        "silence",
                        "smart wanted, speech model not loaded yet: cutting as standard until it is"
                    )
                }
                saidModelMissing = true
                if (active.speech != null) active.speech = null
            }
            else -> {
                saidModelMissing = false
                active.speech = weights?.let { SpeechTrack(SpeechModel(it), sampleRate, SpeechWorker.BACKGROUND) }
                trackedMode = wanted
                val how = if (weights != null) {
                    " (listening for speech; non-speech cut up to a quarter of the speech level)"
                } else {
                    ""
                }
                Diag.log("silence", "cutting as ${wanted.name.lowercase()}$how")
            }
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val active = cutter
        if (active == null) {
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        followMode(active)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val count = remaining / BYTES_PER_SAMPLE
        val frames = count / channels
        if (samples.size < count) samples = ShortArray(count)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, frames * channels)
        inputBuffer.position(inputBuffer.limit())
        write(active.process(samples, frames), active.outputSamples)
        report.after(active)
    }

    override fun onQueueEndOfStream() {
        val active = cutter ?: return
        write(active.endOfStream(), active.outputSamples)
        report.after(active)
    }

    override fun onReset() {
        cutter?.let(report::banked)
        cutter = null
        previousSkippedBy = NOTHING_CUT
        samples = ShortArray(0)
        enabled = false
    }

    private fun write(output: ShortArray, count: Int) {
        if (count == 0) return
        val buffer = replaceOutputBuffer(count * BYTES_PER_SAMPLE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(output, 0, count)
        buffer.position(count * BYTES_PER_SAMPLE)
        buffer.flip()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
    }
}

private const val MICROS_PER_SECOND = 1_000_000L

private val NOTHING_CUT: (Long) -> Long = { 0L }

private fun skippedByOf(segment: SilenceCutter?, rate: Int): (Long) -> Long {
    if (segment == null || rate <= 0) return NOTHING_CUT
    val ledger = segment.heard
    return { heardUs -> ledger.skippedBy(heardUs * rate / MICROS_PER_SECOND) * MICROS_PER_SECOND / rate }
}
