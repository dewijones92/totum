package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MkvTracksBeyondTheHeadTest {

    private val original: ByteArray = javaClass.getResourceAsStream("/cast/avc.mkv")!!.use { it.readBytes() }

    private class Recording(val bytes: ByteArray) : RangeReader {
        val spans = mutableListOf<ByteSpan>()
        override fun read(span: ByteSpan): ByteArray {
            spans += span
            return bytes.copyOfRange(span.first.toInt(), minOf(bytes.size, span.last.toInt() + 1))
        }
    }

    private fun probe(bytes: ByteArray, reader: RangeReader = Recording(bytes)): MkvVideo {
        val head = reader.read(ByteSpan(0, StreamLayouts.HEAD_BYTES - 1))
        return MkvVideo.probe(head, reader) ?: error("no video track")
    }

    private fun assertSameFilm(
        expected: MkvVideo,
        actual: MkvVideo,
        reader: RangeReader,
        readerOfExpected: RangeReader,
    ) {
        assertEquals(expected.codecs, actual.codecs)
        assertEquals(expected.width to expected.height, actual.width to actual.height)
        assertEquals(expected.durationMs, actual.durationMs)
        assertEquals(expected.timeline, actual.timeline)
        assertArrayEquals(expected.init(), actual.init())
        expected.timeline.indices.forEach { n ->
            assertArrayEquals(
                "segment $n",
                expected.segment(n, readerOfExpected),
                actual.segment(n, reader),
            )
        }
    }

    @Test
    fun `Tracks that sit behind a 70 KB Void are found through the SeekHead and fetched by range`() {
        val relaid = MkvFixtures.relayout(original, MkvFixtures.Order.TRACKS_BEHIND_A_BIG_VOID)
        val reader = Recording(relaid)

        val actual = probe(relaid, reader)

        assertSameFilm(probe(original), actual, reader, Recording(original))
        val tracks = MkvFixtures.positions(relaid).getValue("tracks")
        assertTrue(
            "Tracks were not fetched by range at ${tracks.first}: ${reader.spans}",
            reader.spans.any { it.first == tracks.first && it.last >= tracks.first + 8 },
        )
        assertTrue(
            "the whole file was read: ${reader.spans}",
            reader.spans.none { it.last - it.first + 1 > relaid.size / 2 },
        )
    }

    @Test
    fun `Tracks and Info at the very end of the file are found through the SeekHead`() {
        val relaid = MkvFixtures.relayout(original, MkvFixtures.Order.TRACKS_AFTER_THE_CLUSTERS)
        val reader = Recording(relaid)

        val actual = probe(relaid, reader)

        assertSameFilm(probe(original), actual, reader, Recording(original))
        val positions = MkvFixtures.positions(relaid)
        listOf("info", "tracks").forEach { name ->
            val at = positions.getValue(name)
            assertTrue("$name was not fetched by range: ${reader.spans}", reader.spans.any { it.first == at.first })
        }
    }

    @Test
    fun `a source that will not answer the range says why the picture is refused`() {
        val relaid = MkvFixtures.relayout(original, MkvFixtures.Order.TRACKS_BEHIND_A_BIG_VOID)
        val tracks = MkvFixtures.positions(relaid).getValue("tracks")
        val reader = RangeReader { span ->
            if (span.first >= tracks.first) throw IOException("source answered 200, not 206, for bytes ${span.dash}")
            relaid.copyOfRange(span.first.toInt(), minOf(relaid.size, span.last.toInt() + 1))
        }

        try {
            probe(relaid, reader)
            fail("expected a refusal")
        } catch (e: UnreadableLayout) {
            val message = e.message.orEmpty()
            assertTrue(message, message.contains("Tracks are not in its first 65536 bytes"))
            assertTrue(message, message.contains("range"))
            assertTrue(message, message.contains("not 206"))
        }
    }

    @Test
    fun `an MKV with no SeekHead to say where Tracks are is still refused with its reason`() {
        val relaid = MkvFixtures.relayout(original, MkvFixtures.Order.NO_SEEK_HEAD_TRACKS_BEHIND_A_BIG_VOID)

        try {
            probe(relaid)
            fail("expected a refusal")
        } catch (e: UnreadableLayout) {
            val message = e.message.orEmpty()
            assertTrue(message, message.contains("Tracks are not in its first 65536 bytes"))
            assertTrue(message, message.contains("SeekHead"))
        }
    }

    @Test
    fun `an MKV already laid out as mkvmerge does is read from the head alone`() {
        val reader = Recording(original)

        probe(original, reader)

        val outsideTheHead = reader.spans.filter { it.first >= StreamLayouts.HEAD_BYTES }
        assertTrue("only the Cues should be fetched, got $outsideTheHead", outsideTheHead.size <= 2)
    }
}
