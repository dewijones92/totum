package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The boost as Media3 actually drives it — buffers, byte order and all.
 *
 * [LoudnessBoostTest] proves the arithmetic; this proves the plumbing, which is where an audio
 * processor goes wrong in ways no unit test on a `ShortArray` can see. Two of them would be
 * catastrophic and neither would fail to compile: reading the buffer as **big-endian** turns every
 * sample into noise, and forgetting to consume the input or flip the output makes the sink either
 * repeat audio or hear silence.
 *
 * Instrumented because `BaseAudioProcessor` and `AudioFormat` are Media3 Android classes. There is no
 * device behaviour and no playback here, so it runs on every commit.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class BoostingAudioProcessorTest {

    private val rate = 44_100

    private fun format(encoding: Int = C.ENCODING_PCM_16BIT) =
        AudioProcessor.AudioFormat(rate, 1, encoding)

    private fun tone(amplitude: Float, count: Int = rate): ShortArray = ShortArray(count) { i ->
        (sin(2 * PI * TONE_HZ * i / rate) * amplitude * FULL_SCALE).toInt().toShort()
    }

    private fun ShortArray.asBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asShortBuffer().put(this)
        buffer.position(0)
        buffer.limit(size * 2)
        return buffer
    }

    private fun ByteBuffer.toShorts(): ShortArray {
        val shorts = ShortArray(remaining() / 2)
        order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return shorts
    }

    private fun ShortArray.rms(from: Int = 0): Double {
        if (from >= size) return 0.0
        var sum = 0.0
        for (i in from until size) sum += this[i].toDouble() * this[i].toDouble()
        return sqrt(sum / (size - from))
    }

    /** Runs [input] through a configured processor and returns what the sink would receive. */
    private fun run(input: ShortArray, level: VolumeBoost, encoding: Int = C.ENCODING_PCM_16BIT): ShortArray {
        val processor = BoostingAudioProcessor().apply { this.level = level }
        processor.configure(format(encoding))
        processor.flush()
        val buffer = input.asBuffer()
        processor.queueInput(buffer)
        assertEquals("the processor must consume the whole input buffer", 0, buffer.remaining())
        val first = processor.output.toShorts()
        processor.queueEndOfStream()
        return first + processor.output.toShorts()
    }

    /** THE POINT, through the real chain: quiet audio comes out loud. */
    @Test
    fun `a quiet buffer comes out much louder`() {
        val quiet = tone(QUIET, count = 2 * rate)
        val lastHalfSecond = quiet.size - SETTLED

        val out = run(quiet, VolumeBoost.AUTO)

        assertEquals("no samples may be lost or invented", quiet.size, out.size)
        assertTrue(
            "expected a large lift, got ${quiet.rms(lastHalfSecond).toInt()} -> ${out.rms(lastHalfSecond).toInt()}",
            out.rms(lastHalfSecond) > quiet.rms(lastHalfSecond) * MIN_LIFT,
        )
    }

    /**
     * The byte order, which is the mistake that would be obvious on a device and invisible in a type
     * checker. Read big-endian, a clean tone becomes full-scale hash — so the output has to stay
     * *correlated* with the input rather than merely be loud.
     */
    @Test
    fun `the samples are read little-endian rather than turned into noise`() {
        // SOFT, not QUIET: at 1% of full scale every sample sits below WELL_AWAY and there is
        // nothing left to compare, and not MID either, where the automatic gain is 1 and the
        // samples would pass through barely touched.
        val input = tone(SOFT)

        val out = run(input, VolumeBoost.AUTO)

        // Sign agreement: a byte-swapped read scrambles the waveform, so signs would match about half
        // the time. A correct read keeps them almost always in step.
        var agreed = 0
        var counted = 0
        input.indices.forEach { i ->
            if (abs(input[i].toInt()) > WELL_AWAY) {
                counted++
                if ((input[i] > 0) == (out[i] > 0)) agreed++
            }
        }
        assertTrue("nothing to compare", counted > 0)
        assertTrue(
            "only $agreed of $counted samples kept their sign — the buffer is being read byte-swapped",
            agreed > counted * MIN_AGREEMENT,
        )
    }

    /** OFF is a straight passthrough of the same bytes, not a gain of one. */
    @Test
    fun `off passes the buffer through unchanged`() {
        val input = tone(MID)

        assertEquals(input.toList(), run(input, VolumeBoost.OFF).toList())
    }

    /**
     * Anything that is not 16-bit PCM passes through untouched rather than being reinterpreted.
     *
     * Reading float or 8-bit audio as 16-bit samples would not fail, it would produce noise at full
     * volume — which is the worst possible failure for a control whose job is to make things louder.
     */
    @Test
    fun `a format that is not 16-bit pcm is left alone`() {
        val input = tone(MID)

        val out = run(input, VolumeBoost.AUTO, encoding = C.ENCODING_PCM_FLOAT)

        assertEquals(input.toList(), out.toList())
    }

    @Test
    fun `an empty buffer is handled without complaint`() {
        val out = run(ShortArray(0), VolumeBoost.AUTO)

        assertEquals(0, out.size)
    }

    /**
     * The level can change mid-stream, which is what happens when the control is tapped during
     * playback — it must take effect without the processor being reconfigured.
     */
    @Test
    fun `changing the level mid-stream takes effect`() {
        val processor = BoostingAudioProcessor().apply { level = VolumeBoost.OFF }
        processor.configure(format())
        processor.flush()

        val first = tone(QUIET)
        processor.queueInput(first.asBuffer())
        val unboosted = processor.output.toShorts().rms()

        processor.level = VolumeBoost.AUTO
        val second = tone(QUIET)
        processor.queueInput(second.asBuffer())
        val boosted = processor.output.toShorts().rms()

        assertTrue("the new level did not take effect: $unboosted -> $boosted", boosted > unboosted)
    }

    @Test
    fun `a seek forgets the audio held back for the look-ahead`() {
        val processor = BoostingAudioProcessor().apply { level = VolumeBoost.AUTO }
        processor.configure(format())
        processor.flush()
        processor.queueInput(tone(MID).asBuffer())
        processor.output

        processor.flush()
        processor.queueInput(ShortArray(rate).asBuffer())
        val afterSeek = processor.output.toShorts()
        processor.queueEndOfStream()
        val rest = processor.output.toShorts()

        assertTrue("audio from before the seek came out after it", (afterSeek + rest).all { it.toInt() == 0 })
        assertEquals("and nothing else was lost or invented", rate, afterSeek.size + rest.size)
    }

    private companion object {
        const val FULL_SCALE = 32_767f
        const val TONE_HZ = 200f
        const val QUIET = 0.01f

        /** Softly recorded: quiet enough to be given real gain, loud enough to compare sample-wise. */
        const val SOFT = 0.05f
        const val MID = 0.2f

        /** Samples to skip before measuring, so the gain has finished gliding up. */
        const val SETTLED = 22_050

        /** The automatic gain caps at 10x, so a quiet buffer should be getting most of it. */
        const val MIN_LIFT = 8.0

        /** Far enough from zero that a sign change is a real inversion rather than a rounded crossing. */
        const val WELL_AWAY = 1_000

        /** A correct read keeps nearly every sign; a byte-swapped one would sit near half. */
        const val MIN_AGREEMENT = 0.9
    }
}
