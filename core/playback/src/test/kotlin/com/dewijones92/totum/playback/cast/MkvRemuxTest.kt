package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MkvRemuxTest {

    private fun file(name: String): ByteArray = javaClass.getResourceAsStream("/cast/$name")!!.use { it.readBytes() }

    private fun remux(name: String): Pair<MkvVideo, List<ByteArray>> {
        val bytes = file(name)
        val reader = RangeReader { span ->
            bytes.copyOfRange(
                span.first.toInt(),
                minOf(bytes.size, span.last.toInt() + 1)
            )
        }
        val head = reader.read(ByteSpan(0, StreamLayouts.HEAD_BYTES - 1))
        val mkv = MkvVideo.probe(head, reader) ?: error("no video track")
        val segments = mkv.timeline.indices.map { mkv.segment(it, reader) }
        File("build/tmp/remux-$name.mp4").apply { parentFile.mkdirs() }
            .writeBytes(segments.fold(mkv.init()) { all, segment -> all + segment })
        return mkv to segments
    }

    private fun ByteArray.boxes(type: String): List<Int> =
        (0..size - 4).filter { String(this, it, 4, Charsets.US_ASCII) == type }

    private fun ByteArray.u32(at: Int): Long = (0 until 4).fold(
        0L
    ) { v, k -> (v shl 8) or (this[at + k].toLong() and 0xFF) }

    private fun ByteArray.u64(at: Int): Long = (u32(at) shl 32) or u32(at + 4)

    @Test
    fun `an HEVC MKV whose first track is audio cuts into one segment per keyframe with every frame kept`() {
        val (mkv, segments) = remux("hevc-audio-first.mkv")

        assertTrue(mkv.codecs, mkv.codecs.startsWith("hev1."))
        assertEquals(320 to 180, mkv.width to mkv.height)
        assertEquals(4, segments.size)
        assertEquals(100L, segments.sumOf { it.u32(it.boxes("trun").single() + 8) })
    }

    @Test
    fun `segments sit end to end on the cue timeline`() {
        val (mkv, segments) = remux("hevc-audio-first.mkv")

        val decodeStarts = segments.map { it.u64(it.boxes("tfdt").single() + 8) }
        assertEquals(mkv.timeline.map { it.startMs }, decodeStarts)
        assertEquals(mkv.durationMs, mkv.timeline.sumOf { it.durationMs })
    }

    @Test
    fun `an AVC MKV remuxes too`() {
        val (mkv, segments) = remux("avc.mkv")

        assertTrue(mkv.codecs, mkv.codecs.startsWith("avc1."))
        assertEquals(100L, segments.sumOf { it.u32(it.boxes("trun").single() + 8) })
    }
}
