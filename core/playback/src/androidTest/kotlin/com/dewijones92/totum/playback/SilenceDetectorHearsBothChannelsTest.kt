package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The silence detector must listen to every channel, and had never been tested at all.
 *
 * `isQuiet` walked the buffer with `index += STRIDE_BYTES` where `STRIDE_BYTES = 64`. Buffers are
 * frame-aligned and 16-bit stereo is 4 bytes per frame, and 64 is a multiple of 4 — so **every** sampled
 * offset was `position() mod 4`: channel 0, always, for the life of the stream. The real measured format
 * is `ch=2`.
 *
 * So a left channel sitting below the threshold latched silence regardless of what the right channel was
 * doing. That is not only digital silence: `SILENCE_THRESHOLD = 1024` is about -30 dBFS, which
 * hard-panned dialogue, a mono-on-one-side upload or one dead mic channel clears easily. Once latched,
 * playback runs at 4x through audible audio, and only toggling the feature off, a track change or a seek
 * escapes — then twenty buffers later it re-enters.
 *
 * Second defect in the same method, and the reason the class comment was untrue: `onConfigure` says
 * anything not 16-bit PCM "passes through unexamined" but only LOGS, while `queueInput` calls `getShort`
 * unconditionally. The sibling `BoostingAudioProcessor` really does guard, and its KDoc asserts the two
 * behave the same way.
 *
 * Instrumented for the same reason as `BoostingAudioProcessorTest`: `BaseAudioProcessor` and `AudioFormat`
 * are Media3 Android classes. No device behaviour, no network, so it runs on every commit. Note that the
 * sibling's format-guard case uses `channelCount = 1`, which is exactly why the stereo bias was never
 * exercised.
 *
 * ⚠️ Deliberately NOT covered here: the entry/exit constants (`BUFFERS_TO_ENTER`, no exit hysteresis,
 * the `onFlush` reset) are already recorded in `docs/todos/skip-silence-smoothness.md`, and this is a
 * VIDEO-only path — `PlaybackService.onSilenceChanged` early-returns for audio-only content, which uses
 * Media3's own all-channel processor.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class SilenceDetectorHearsBothChannelsTest {

    private val rate = 44_100
    private val reports = mutableListOf<Boolean>()

    private fun processor(channels: Int, encoding: Int = C.ENCODING_PCM_16BIT): SilenceDetectingAudioProcessor {
        val processor = SilenceDetectingAudioProcessor { reports += it }
        processor.configure(AudioProcessor.AudioFormat(rate, channels, encoding))
        processor.flush()
        return processor
    }

    /** Interleaved stereo: [left] in channel 0, [right] in channel 1. */
    private fun stereo(left: Short, right: Short, frames: Int = FRAMES): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 2 * 2).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = buffer.asShortBuffer()
        repeat(frames) {
            shorts.put(left)
            shorts.put(right)
        }
        buffer.position(0).limit(frames * 2 * 2)
        return buffer
    }

    private fun SilenceDetectingAudioProcessor.feed(buffer: ByteBuffer, times: Int) {
        repeat(times) {
            buffer.position(0)
            queueInput(buffer)
            getOutput()
        }
    }

    /** THE bug: a silent LEFT channel with loud audio on the right is not silence. */
    @Test
    fun loudRightChannelIsNotSilence() {
        val processor = processor(channels = 2)

        processor.feed(stereo(left = 0, right = LOUD), times = ENOUGH_TO_LATCH)

        assertFalse(
            "audio is playing on the right channel — reporting silence speeds it up to 4x. Reports: $reports",
            reports.contains(true),
        )
    }

    /** Genuine silence on both channels still latches, so the fix cannot just disable detection. */
    @Test
    fun silenceOnBothChannelsIsStillDetected() {
        val processor = processor(channels = 2)

        processor.feed(stereo(left = 0, right = 0), times = ENOUGH_TO_LATCH)

        assertTrue("real stereo silence must still be found. Reports: $reports", reports.contains(true))
    }

    /** And a loud left channel was already fine — kept so the fix cannot invert the sampling. */
    @Test
    fun loudLeftChannelIsNotSilence() {
        val processor = processor(channels = 2)

        processor.feed(stereo(left = LOUD, right = 0), times = ENOUGH_TO_LATCH)

        assertFalse(reports.contains(true))
    }

    /** Mono still works: the frame is one sample wide, and that is the common podcast case. */
    @Test
    fun monoSilenceIsDetected() {
        val processor = processor(channels = 1)
        val buffer = ByteBuffer.allocateDirect(FRAMES * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(0).limit(FRAMES * 2)

        processor.feed(buffer, times = ENOUGH_TO_LATCH)

        assertTrue("mono silence must still be found. Reports: $reports", reports.contains(true))
    }

    /**
     * A format that is not 16-bit PCM must not be INSPECTED as though it were.
     *
     * The class comment claims this already; only `onConfigure`'s log did. Reading 8-bit or float samples
     * with `getShort` produces meaningless numbers, and meaningless numbers below 1024 latch silence.
     */
    @Test
    fun anUnsupportedEncodingIsNotJudged() {
        val processor = processor(channels = 2, encoding = C.ENCODING_PCM_8BIT)
        val buffer = ByteBuffer.allocateDirect(FRAMES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(0).limit(FRAMES)

        processor.feed(buffer, times = ENOUGH_TO_LATCH)

        assertEquals(
            "an encoding we cannot read must produce no silence verdict at all. Reports: $reports",
            emptyList<Boolean>(),
            reports,
        )
    }

    private companion object {
        const val FRAMES = 1024

        /** Comfortably past `BUFFERS_TO_ENTER`. */
        const val ENOUGH_TO_LATCH = 30

        /** Well above `SILENCE_THRESHOLD` (1024). */
        const val LOUD: Short = 12_000
    }
}
