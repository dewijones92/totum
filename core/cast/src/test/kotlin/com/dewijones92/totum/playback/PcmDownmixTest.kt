package com.dewijones92.totum.playback

import com.dewijones92.totum.playback.cast.PcmDownmix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmDownmixTest {

    private fun pcm(vararg frames: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames.sumOf { it.size } * 2).order(ByteOrder.nativeOrder())
        frames.forEach { frame -> frame.forEach(buffer::putShort) }
        return buffer.apply { flip() }
    }

    private fun samples(buffer: ByteBuffer): List<Int> {
        val shorts = buffer.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
        return List(shorts.remaining()) { shorts.get(it).toInt() }
    }

    @Test
    fun `five point one becomes stereo with the centre in both and each side on its own side`() {
        val centreOnly = shortArrayOf(0, 0, 10_000, 0, 0, 0)
        val frontLeftOnly = shortArrayOf(10_000, 0, 0, 0, 0, 0)
        val rearRightOnly = shortArrayOf(0, 0, 0, 0, 0, 10_000)
        val lfeOnly = shortArrayOf(0, 0, 0, 30_000, 0, 0)

        val out =
            samples(PcmDownmix.toChannels(pcm(centreOnly, frontLeftOnly, rearRightOnly, lfeOnly), from = 6, to = 2))

        assertEquals(8, out.size)
        assertEquals(out[0], out[1])
        assertTrue("centre ${out[0]}", out[0] in 2_800..3_000)
        assertTrue("front left ${out[2]}", out[2] in 4_000..4_300)
        assertEquals(0, out[3])
        assertEquals(0, out[4])
        assertTrue("rear right ${out[5]}", out[5] in 2_800..3_000)
        assertEquals(listOf(0, 0), out.subList(6, 8))
    }

    @Test
    fun `everything at full scale does not clip`() {
        val loud = ShortArray(6) { Short.MAX_VALUE }

        val out = samples(PcmDownmix.toChannels(pcm(loud), from = 6, to = 2))

        assertTrue(out.all { it in 30_000..Short.MAX_VALUE.toInt() })
    }

    @Test
    fun `stereo to mono averages and stereo to stereo is unchanged`() {
        val stereo = pcm(shortArrayOf(1_000, 3_000))

        assertEquals(listOf(2_000), samples(PcmDownmix.toChannels(stereo, from = 2, to = 1)))
        assertEquals(listOf(1_000, 3_000), samples(PcmDownmix.toChannels(pcm(shortArrayOf(1_000, 3_000)), 2, 2)))
    }
}
