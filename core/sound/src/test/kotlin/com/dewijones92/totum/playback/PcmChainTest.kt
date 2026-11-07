package com.dewijones92.totum.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

class PcmChainTest {

    private val rate = 16_000

    private fun noise(seconds: Double, sigma: Double, seed: Long): ShortArray {
        val random = Random(seed)
        return ShortArray((seconds * rate).toInt()) { (random.nextGaussian() * sigma).toInt().toShort() }
    }

    private val talk: ShortArray = noise(1.0, 4_000.0, 1) + ShortArray(rate * 3) + noise(1.0, 4_000.0, 2)

    private fun run(chain: PcmChain, input: ShortArray, chunk: Int): ShortArray {
        val out = ArrayList<Short>()
        var at = 0
        while (at < input.size) {
            val end = minOf(at + chunk, input.size)
            chain.process(input.copyOfRange(at, end)).forEach(out::add)
            at = end
        }
        chain.finish().forEach(out::add)
        return out.toShortArray()
    }

    @Test
    fun `with nothing switched on the sound is passed through sample for sample`() {
        val chain = PcmChain(rate, 1, skipSilence = false, boost = VolumeBoost.OFF)

        assertFalse(chain.changesSound)
        assertArrayEquals(talk, run(chain, talk, 1_000))
        assertEquals(0L, chain.skippedFrames)
    }

    @Test
    fun `skipping silence shortens a long pause and counts what it took out`() {
        val chain = PcmChain(rate, 1, skipSilence = true, boost = VolumeBoost.OFF)

        val out = run(chain, talk, 2_048)

        assertTrue(chain.changesSound)
        assertTrue("skipped ${chain.skippedFrames}", chain.skippedFrames > rate)
        assertEquals(talk.size.toLong(), out.size + chain.skippedFrames)
    }

    @Test
    fun `how the audio arrives does not change what comes out`() {
        val whole = run(PcmChain(rate, 1, true, VolumeBoost.AUTO), talk, talk.size)
        val chunked = run(PcmChain(rate, 1, true, VolumeBoost.AUTO), talk, 700)

        assertArrayEquals(whole, chunked)
    }

    @Test
    fun `a boost lifts a quiet recording without losing frames`() {
        val quiet = noise(4.0, 300.0, 3)
        val chain = PcmChain(rate, 1, skipSilence = false, boost = VolumeBoost.AUTO)

        val out = run(chain, quiet, 2_048)

        assertEquals(quiet.size, out.size)
        assertTrue(out.maxOf { abs(it.toInt()) } > quiet.maxOf { abs(it.toInt()) })
    }

    @Test
    fun `stereo is kept as pairs`() {
        val stereo = ShortArray(talk.size * 2) { talk[it / 2] }

        val out = run(PcmChain(rate, 2, true, VolumeBoost.OFF), stereo, 4_096)

        assertEquals(0, out.size % 2)
    }
}
