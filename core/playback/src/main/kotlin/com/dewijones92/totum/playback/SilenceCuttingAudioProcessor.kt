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

    val heard: HeardCuts? get() = cutter?.heard

    var flushes: Long = 0L
        private set

    var carriedUs: Long = 0L
        private set

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
        previous?.let(report::banked)
        carriedUs = framesToUs(previous?.skippedFrames ?: 0L)
        flushes++
        cutter = if (isActive) {
            sampleRate = inputAudioFormat.sampleRate
            report.sampleRate = sampleRate
            Diag.log(
                "silence",
                "cutting pauses over ${SilenceCutter.MIN_SILENCE_MS}ms to ${2 * SilenceCutter.PAD_MS}ms " +
                    "(rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}, " +
                    "cut level ${previous?.cutLevel ?: SilenceCutter.FLOOR} of at most ${SilenceCutter.THRESHOLD})",
            )
            SilenceCutter(
                inputAudioFormat.sampleRate,
                inputAudioFormat.channelCount.coerceAtLeast(1),
                startLevel = previous?.level ?: 0f,
            )
        } else {
            null
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
        const val MICROS_PER_SECOND = 1_000_000L
    }
}
