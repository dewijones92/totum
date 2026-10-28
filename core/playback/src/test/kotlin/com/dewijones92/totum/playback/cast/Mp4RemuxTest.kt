package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Mp4RemuxTest {

    private fun file(name: String): ByteArray = javaClass.getResourceAsStream("/cast/$name")!!.use { it.readBytes() }

    private class Frame(val ptsMs: Long, val size: Long, val key: Boolean)

    private fun remux(name: String, headBytes: Long = StreamLayouts.HEAD_BYTES): Pair<RemuxedVideo, List<ByteArray>> {
        val bytes = file(name)
        val reads = mutableListOf<ByteSpan>()
        val reader = RangeReader { span ->
            reads += span
            bytes.copyOfRange(span.first.toInt(), minOf(bytes.size, span.last.toInt() + 1))
        }
        val head = bytes.copyOfRange(0, minOf(bytes.size, headBytes.toInt()))
        val video = Mp4Video.probe(head, reader) ?: error("no video track")
        val segments = video.timeline.indices.map { video.segment(it, reader) }
        File("build/tmp/remux-$name").apply { parentFile.mkdirs() }
            .writeBytes(segments.fold(video.init()) { all, segment -> all + segment })
        return video to segments
    }

    private fun ByteArray.boxes(type: String): List<Int> =
        (0..size - 4).filter { String(this, it, 4, Charsets.US_ASCII) == type }

    private fun ByteArray.u32(at: Int): Long = (0 until 4).fold(
        0L
    ) { v, k -> (v shl 8) or (this[at + k].toLong() and 0xFF) }

    private fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

    private fun frames(segment: ByteArray): List<Frame> {
        val tfdt = segment.boxes("tfdt").single()
        var dts = segment.u64(tfdt + 8)
        val trun = segment.boxes("trun").single()
        val count = segment.u32(trun + 8).toInt()
        return (0 until count).map { i ->
            val at = trun + 16 + i * 16
            val duration = segment.u32(at)
            val size = segment.u32(at + 4)
            val key = segment.u32(at + 8) == 0x02000000L
            val offset = segment.u32(at + 12).toInt()
            Frame(dts + offset, size, key).also { dts += duration }
        }
    }

    private fun assertWholeFilm(video: RemuxedVideo, segments: List<ByteArray>) {
        assertTrue(video.codecs, video.codecs.startsWith("avc1."))
        assertEquals(320 to 180, video.width to video.height)
        assertEquals(3, segments.size)
        val all = segments.flatMap(::frames)
        assertEquals(150, all.size)
        assertEquals(31_682L, all.sumOf { it.size })
        segments.forEach { assertTrue("a segment opens on a keyframe", frames(it).first().key) }
        assertEquals((0 until 150).map { it * 40L }, all.map { it.ptsMs }.sorted())
        assertNotEquals(
            "B-frames: decode order is not display order",
            all.map { it.ptsMs }.sorted(),
            all.map { it.ptsMs }
        )
    }

    @Test
    fun `an MP4 with its index at the front becomes TV segments with every frame kept`() {
        val (video, segments) = remux("avc-bframes-moov-faststart.mp4")

        assertWholeFilm(video, segments)
    }

    @Test
    fun `an MP4 with its index at the end is found by walking to it`() {
        val (video, segments) = remux("avc-bframes-moov-end.mp4")

        assertWholeFilm(video, segments)
    }

    @Test
    fun `an index longer than the first read is read in full`() {
        val (video, segments) = remux("avc-bframes-moov-faststart.mp4", headBytes = 1_024)

        assertWholeFilm(video, segments)
    }

    @Test
    fun `segments sit end to end across the whole film`() {
        val (video, _) = remux("avc-bframes-moov-faststart.mp4")

        video.timeline.zipWithNext().forEach { (a, b) -> assertEquals(a.startMs + a.durationMs, b.startMs) }
        assertEquals(0L, video.timeline.first().startMs)
        assertEquals(6_000L, video.timeline.last().let { it.startMs + it.durationMs })
    }
}
