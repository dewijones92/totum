package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Fmp4AacTest {

    private val format = AacFormat(48000, 2, byteArrayOf(0x11, 0x90.toByte()))

    private fun ByteArray.u32(at: Int): Long = (0 until 4).fold(
        0L
    ) { v, k -> (v shl 8) or (this[at + k].toLong() and 0xFF) }

    private fun ByteArray.find(type: String): Int =
        (0..size - 4).first { String(this, it, 4, Charsets.US_ASCII) == type }

    @Test
    fun `the codec string is mp4a 40 and the object type from the config`() {
        assertEquals("mp4a.40.2", AacFormat(44100, 2, byteArrayOf(0x12, 0x10)).codecs)
        assertEquals("mp4a.40.5", AacFormat(44100, 2, byteArrayOf(0x2B, 0x10)).codecs)
        assertEquals("mp4a.40.2", AacFormat(44100, 2, ByteArray(0)).codecs)
    }

    @Test
    fun `the init segment is an ftyp then a moov holding an mp4a entry with the config`() {
        val init = Fmp4Aac.init(format)

        assertEquals("ftyp", String(init, 4, 4, Charsets.US_ASCII))
        val moov = init.find("moov")
        assertTrue(moov > 0)
        val mp4a = init.find("mp4a")
        assertEquals(2, (init[mp4a + 4 + 6 + 2 + 8].toInt() shl 8) or (init[mp4a + 4 + 6 + 2 + 8 + 1].toInt() and 0xFF))
        assertTrue(init.contains(format.audioSpecificConfig))
        assertTrue(init.contains("esds".toByteArray()))
        assertTrue(init.contains("soun".toByteArray()))
    }

    @Test
    fun `a media segment is a moof whose trun data offset lands on the first byte of the mdat payload`() {
        val frames = listOf(ByteArray(100) { 1 }, ByteArray(80) { 2 }, ByteArray(120) { 3 })

        val segment = Fmp4Aac.segment(7, 9_216, frames)

        val moofSize = segment.u32(0).toInt()
        assertEquals("moof", String(segment, 4, 4, Charsets.US_ASCII))
        assertEquals("mdat", String(segment, moofSize + 4, 4, Charsets.US_ASCII))
        assertEquals(moofSize + 8L + 300, segment.size.toLong())
        assertEquals(300L + 8, segment.u32(moofSize))
        val trun = segment.find("trun")
        assertEquals(3L, segment.u32(trun + 8))
        assertEquals(moofSize + 8L, segment.u32(trun + 12))
        assertEquals(1024L, segment.u32(trun + 16))
        assertEquals(100L, segment.u32(trun + 20))
        assertEquals(80L, segment.u32(trun + 28))
        assertEquals(120L, segment.u32(trun + 36))
        assertEquals(frames.flatMap { it.toList() }, segment.copyOfRange(moofSize + 8, segment.size).toList())
    }

    @Test
    fun `the sequence number and the base decode time are written where a player reads them`() {
        val segment = Fmp4Aac.segment(7, 9_216, listOf(ByteArray(10)))

        assertEquals(7L, segment.u32(segment.find("mfhd") + 8))
        val tfdt = segment.find("tfdt")
        assertEquals(0L, segment.u32(tfdt + 8))
        assertEquals(9_216L, segment.u32(tfdt + 12))
    }

    @Test
    fun `a segment with no frames is a programming error not an empty file`() {
        val failure = runCatching { Fmp4Aac.segment(1, 0, emptyList()) }.exceptionOrNull()

        assertTrue(failure is UnsupportedOperationException)
    }
}
