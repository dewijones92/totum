package com.dewijones92.totum.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Passes audio through untouched and reports when it goes quiet, so silence can be
 * handled by **changing the playback rate** rather than by removing samples.
 *
 * That distinction is the whole point. Media3's `SilenceSkippingAudioProcessor`
 * shortens the audio stream but not the video clock, so on a video the audio runs
 * ahead of the picture (measured: ~6s over a 20s clip) — which is why skip-silence
 * used to be audio-only. Speeding up retimes audio *and* video together, so it cannot
 * desync, and there is no seeking, so no keyframe stutter either.
 *
 * **Why acting immediately is accurate enough:** this sits in the sink's chain, and the
 * sink consumes buffers in real time — the audio track holds only a few hundred
 * milliseconds. So "just saw silence here" means "about to be heard", and the constant
 * lead is small relative to a gap worth skipping. Short gaps simply get a brief nudge.
 */
@OptIn(markerClass = [UnstableApi::class])
@UnstableApi
internal class SilenceDetectingAudioProcessor(
    private val onSilenceChanged: (Boolean) -> Unit,
) : BaseAudioProcessor() {

    private var silentBuffers = 0
    private var reportedSilent = false
    private var lastPeak = 0
    private var silenceCount = 0L

    /**
     * Channels per frame, so every one of them is listened to.
     *
     * `isQuiet` walked the buffer with a 64-byte stride. A frame of 16-bit stereo is 4 bytes and 64 is a
     * multiple of 4, so every sampled offset was `position() mod 4` -- channel 0, always. A left channel
     * below the threshold latched silence whatever the right channel was doing, and at ~-30 dBFS that
     * covers hard-panned dialogue or one dead mic channel, not just digital silence.
     */
    private var channels = 1

    /**
     * Whether the buffers can be read as 16-bit samples at all.
     *
     * The comment below has always claimed anything else "passes through unexamined", and only the log
     * did: `queueInput` called `getShort` unconditionally, and meaningless numbers below the threshold
     * latch silence. The sibling `BoostingAudioProcessor` really does guard, and its KDoc says the two
     * behave the same way.
     */
    private var readable = true

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        readable = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        Diag.log(
            "silence",
            "configured enc=${inputAudioFormat.encoding} " +
                "rate=${inputAudioFormat.sampleRate} ch=${inputAudioFormat.channelCount}" +
                if (readable) "" else " — not 16-bit PCM, so silence will not be judged",
        )
        // 16-bit PCM is what the sink hands us after decoding; anything else passes
        // through unexamined rather than being misread as silence.
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (!readable) {
            // Passed through without a verdict, as the configure comment has always claimed.
            val untouched = replaceOutputBuffer(remaining)
            untouched.put(inputBuffer)
            untouched.flip()
            return
        }
        val quiet = inputBuffer.isQuiet()

        // Entering silence needs a few consecutive quiet buffers so a momentary dip
        // between words doesn't trigger; leaving is immediate, so speech is never
        // clipped by the speed-up lingering.
        if (quiet) {
            silentBuffers++
            if (!reportedSilent && silentBuffers >= BUFFERS_TO_ENTER) {
                reportedSilent = true
                // Silence is entered every few seconds in speech, so logging each one
                // would flood the crash-report event trail and evict what matters.
                // The first tells you detection works at all; a periodic count keeps
                // proving it without drowning the trail.
                silenceCount++
                if (silenceCount == 1L || silenceCount % SILENCE_LOG_EVERY == 0L) {
                    Diag.log("silence", "entering silence #$silenceCount (peak=$lastPeak)")
                }
                onSilenceChanged(true)
            }
        } else {
            silentBuffers = 0
            if (reportedSilent) {
                reportedSilent = false
                onSilenceChanged(false)
            }
        }

        // Pass through unchanged: this processor only observes.
        val output = replaceOutputBuffer(remaining)
        output.put(inputBuffer)
        output.flip()
    }

    override fun onFlush() {
        // A seek invalidates what we thought we were hearing.
        silentBuffers = 0
        if (reportedSilent) {
            reportedSilent = false
            onSilenceChanged(false)
        }
    }

    /** True when every sampled frame sits below the silence threshold. */
    private fun ByteBuffer.isQuiet(): Boolean {
        val order = order()
        order(ByteOrder.LITTLE_ENDIAN)
        try {
            var index = position()
            val end = limit()
            // Sampling FRAMES rather than bytes, and every channel within the sampled frame. Striding by
            // bytes only ever read channel 0 on stereo, because the stride and the frame size share a
            // factor -- see [channels].
            val frameBytes = channels * BYTES_PER_SAMPLE
            val stride = (STRIDE_BYTES / frameBytes).coerceAtLeast(1) * frameBytes
            var peak = 0
            while (index + frameBytes <= end) {
                for (channel in 0 until channels) {
                    val at = index + channel * BYTES_PER_SAMPLE
                    if (at + BYTES_PER_SAMPLE > end) break
                    val sample = abs(getShort(at).toInt())
                    if (sample > peak) peak = sample
                }
                index += stride
            }
            lastPeak = peak
            return peak <= SILENCE_THRESHOLD
        } finally {
            order(order)
        }
    }

    private companion object {
        /**
         * 16-bit amplitude below which audio counts as silence — the same value
         * Media3's own `SilenceSkippingAudioProcessor` defaults to. Measured on a real
         * clip, a stricter 128 never triggered: a recording's quiet passages still sit
         * well above the theoretical noise floor.
         */
        const val SILENCE_THRESHOLD = 1024

        /** One 16-bit sample. */
        const val BYTES_PER_SAMPLE = 2

        /** How often a repeat silence entry is logged, so the event trail stays useful. */
        const val SILENCE_LOG_EVERY = 50L

        /**
         * Consecutive quiet buffers before we call it silence. A buffer measured ~25ms, so
         * this is ~500ms.
         *
         * It was 6 (~150ms), copying Media3's own minimum, with a comment asserting that a
         * gap between words wouldn't trip it. Measurement said otherwise: on a 160-minute
         * podcast-style video this fired **242 times in 90 seconds**, flapping 1x/4x within
         * milliseconds —
         *
         *     19:44:00.894 silent=true  speed=4.0
         *     19:44:00.974 silent=false speed=1.0
         *     19:44:00.994 silent=true  speed=4.0
         *
         * Every change reconfigures the audio sink, which is heard as stutter and, on a
         * phone with a slower link, drags the buffer down. Inter-word gaps run 50-200ms
         * while real dead air runs past half a second, so raising the threshold separates
         * them. Leaving silence stays immediate — hysteresis on the way out would clip the
         * first syllable of speech, which is a worse bug than the one being fixed.
         */
        const val BUFFERS_TO_ENTER = 20

        /** Bytes between sampled frames (2 bytes per sample, so this is every 32nd frame). */
        const val STRIDE_BYTES = 64
    }
}
