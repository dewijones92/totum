package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SidxTest {

    private fun file(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/cast/$name")).use { it.readBytes() }

    private fun segmentsOf(name: String): Pair<ByteArray, List<SidxSegment>> {
        val bytes = file(name)
        val layout = StreamLayouts.probe { span ->
            bytes.copyOfRange(span.first.toInt(), minOf(bytes.size, span.last.toInt() + 1))
        }
        val box = bytes.copyOfRange(layout.index.first.toInt(), layout.index.last.toInt() + 1)
        return bytes to Sidx.read(box, layout.index.first)
    }

    @Test
    fun `a fragmented MP4 lists one segment per fragment, each starting at a moof, together reaching the end`() {
        val (bytes, segments) = segmentsOf("avc-fragmented-sidx.mp4")

        assertEquals(3, segments.size)
        segments.forEach { assertEquals("moof", String(bytes, it.bytes.first.toInt() + 4, 4)) }
        assertEquals("mfra", String(bytes, segments.last().bytes.last.toInt() + 1 + 4, 4))
        segments.zipWithNext().forEach { (a, b) -> assertEquals(a.bytes.last + 1, b.bytes.first) }
        assertEquals(6_000_000L, segments.sumOf { it.durationUs })
    }

    @Test
    fun `a version 1 index of two references is read as two segments of a second each`() {
        val (_, segments) = segmentsOf("hvc1-320x180.mp4")

        assertEquals(2, segments.size)
        assertTrue(segments.sumOf { it.durationUs } in 1_900_000L..2_100_000L)
    }

    @Test
    fun `a box that is not a sidx is refused by name`() {
        val ftyp = file("avc-fragmented-sidx.mp4").copyOf(40)

        try {
            Sidx.read(ftyp, 0)
            fail("expected an unreadable layout")
        } catch (e: UnreadableLayout) {
            assertTrue(e.message, e.message!!.contains("ftyp"))
        }
    }

    @Test
    fun `a sidx that points at another sidx is refused rather than cut wrongly`() {
        val bytes = file("avc-fragmented-sidx.mp4")
        val box = bytes.copyOfRange(778, 778 + 76)
        box[40] = (box[40].toInt() or 0x80).toByte()

        try {
            Sidx.read(box, 778)
            fail("expected an unreadable layout")
        } catch (e: UnreadableLayout) {
            assertTrue(e.message, e.message!!.contains("another sidx"))
        }
    }
}
