package com.dewijones92.totum.playback.audioquality

import com.dewijones92.totum.playback.SilenceCutter
import com.dewijones92.totum.playback.audioquality.RealSpeech.RATE
import com.dewijones92.totum.playback.audioquality.RealSpeech.clip
import com.dewijones92.totum.playback.audioquality.RealSpeech.cut
import com.dewijones92.totum.playback.audioquality.RealSpeech.gained
import com.dewijones92.totum.playback.audioquality.RealSpeech.peak
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class RealSpeechChainTest {

    @Test
    fun `a quiet recording cut and then boosted loses its pauses and never clips`() {
        val input = gained(clip, -24.0, hissSigma = 3.0)

        val cutResult = cut(input)
        val boostedResult = RealSpeech.boosted(cutResult.output)

        val removedMs = RealSpeech.millisOf(cutResult.skippedFrames.toInt())
        assertTrue("removed ${removedMs}ms in ${cutResult.gaps} pauses", removedMs >= REMOVED_MS)
        assertEquals(0L, boostedResult.clipped)
        assertTrue(peak(boostedResult.output) <= CEILING_SAMPLE)
    }

    @Test
    fun `every frame in is either played or counted as skipped`() {
        val result = cut(clip)

        assertEquals(clip.size.toLong(), result.output.size + result.skippedFrames)
    }

    @Test
    fun `how the audio arrives does not change what is cut`() {
        val random = Random(SEED)
        val whole = cut(clip) { clip.size }
        val tiny = cut(clip) { 1 + random.nextInt(TINY_CHUNK) }
        val device = cut(clip) { DEVICE_CHUNK }

        assertArrayEquals(whole.output, tiny.output)
        assertArrayEquals(whole.output, device.output)
    }

    @Test
    fun `stereo speech is cut exactly as the same speech in mono`() {
        val stereo = ShortArray(clip.size * 2) { clip[it / 2] }

        val mono = cut(clip)
        val both = cut(stereo, channels = 2)

        assertEquals(mono.skippedFrames, both.skippedFrames)
        assertArrayEquals(mono.output, ShortArray(both.output.size / 2) { both.output[it * 2] })
    }

    @Test
    fun `no cut leaves a click`() {
        val result = cut(gained(clip, -12.0, hissSigma = 3.0))

        val jumpIn = biggestJump(gained(clip, -12.0, hissSigma = 3.0))
        val jumpOut = biggestJump(result.output)
        assertTrue("biggest step $jumpOut against $jumpIn in the recording", jumpOut <= jumpIn)
        assertTrue(result.gaps > MIN_GAPS)
    }

    @Test
    fun `the cutter holds exactly its look-ahead and no more`() {
        val cutter = SilenceCutter(RATE, 1)
        val out = cutter.process(clip.copyOfRange(0, RATE), RATE)

        assertEquals(RATE - cutter.lookaheadFrames, cutter.outputFrames.toInt() + cutter.skippedFrames.toInt())
        assertTrue(out.isNotEmpty())
    }

    private fun biggestJump(samples: ShortArray): Int {
        var biggest = 0
        for (k in 1 until samples.size) biggest = maxOf(biggest, abs(samples[k] - samples[k - 1]))
        return biggest
    }

    private companion object {
        const val REMOVED_MS = 3_000L
        const val CEILING_SAMPLE = 31_130
        const val SEED = 9L
        const val TINY_CHUNK = 700
        const val DEVICE_CHUNK = 2_048
        const val MIN_GAPS = 20L
    }
}
