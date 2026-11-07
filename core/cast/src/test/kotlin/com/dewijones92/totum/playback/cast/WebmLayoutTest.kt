package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebmLayoutTest {

    private fun idBytes(id: Long): ByteArray {
        var n = 1
        while (id shr (8 * n) != 0L) n++
        return ByteArray(n) { (id shr (8 * (n - 1 - it))).toByte() }
    }

    private fun sizeBytes(size: Int): ByteArray = byteArrayOf(
        (0x10 or (size shr 24)).toByte(),
        (size shr 16).toByte(),
        (size shr 8).toByte(),
        size.toByte(),
    )

    private fun element(id: Long, body: ByteArray = ByteArray(0)) = idBytes(id) + sizeBytes(body.size) + body

    private fun uintElement(id: Long, value: Int) = element(id, byteArrayOf((value shr 8).toByte(), value.toByte()))

    private fun text(id: Long, value: String) = element(id, value.toByteArray(Charsets.US_ASCII))

    private fun videoTrack(codecId: String, width: Int, height: Int, private: ByteArray? = null) = element(
        Ebml.TRACKS,
        element(
            Ebml.TRACK_ENTRY,
            text(Ebml.CODEC_ID, codecId) +
                (private?.let { element(Ebml.CODEC_PRIVATE, it) } ?: ByteArray(0)) +
                element(Ebml.VIDEO, uintElement(Ebml.PIXEL_WIDTH, width) + uintElement(Ebml.PIXEL_HEIGHT, height)),
        ),
    )

    private fun audioTrack(codecId: String, hz: Double, channels: Int) = element(
        Ebml.TRACKS,
        element(
            Ebml.TRACK_ENTRY,
            text(Ebml.CODEC_ID, codecId) + element(
                Ebml.AUDIO,
                element(Ebml.SAMPLING_FREQUENCY, java.nio.ByteBuffer.allocate(8).putDouble(hz).array()) +
                    element(Ebml.CHANNELS, byteArrayOf(channels.toByte())),
            ),
        ),
    )

    private class Built(val file: ByteArray, val cuesAt: Long, val cuesLength: Int)

    private val ebmlHeader = element(Ebml.EBML_HEADER, ByteArray(6))

    private fun withSeekHead(tracks: ByteArray, cuesBody: ByteArray = ByteArray(20)): Built {
        val seekHeadLength = element(
            Ebml.SEEK_HEAD,
            element(Ebml.SEEK, element(Ebml.SEEK_ID, idBytes(Ebml.CUES)) + element(Ebml.SEEK_POSITION, ByteArray(4))),
        ).size
        val cuesPositionInSegment = seekHeadLength + tracks.size
        val seekHead = element(
            Ebml.SEEK_HEAD,
            element(
                Ebml.SEEK,
                element(Ebml.SEEK_ID, idBytes(Ebml.CUES)) +
                    element(Ebml.SEEK_POSITION, java.nio.ByteBuffer.allocate(4).putInt(cuesPositionInSegment).array()),
            ),
        )
        val cues = element(Ebml.CUES, cuesBody)
        val segmentBody = seekHead + tracks + cues
        val segmentHeader = idBytes(Ebml.SEGMENT) + sizeBytes(segmentBody.size)
        val file = ebmlHeader + segmentHeader + segmentBody
        val cuesAt = (ebmlHeader.size + segmentHeader.size + cuesPositionInSegment).toLong()
        return Built(file, cuesAt, cues.size)
    }

    private fun reader(file: ByteArray) = RangeReader { span ->
        file.copyOfRange(span.first.toInt(), minOf(file.size, span.last.toInt() + 1))
    }

    private fun layoutOf(built: Built) = WebmLayout.read(built.file, reader(built.file))

    @Test
    fun `a VP9 video names its codec, size, init span and the span of its cues`() {
        val built = withSeekHead(videoTrack("V_VP9", 1280, 720))

        val layout = layoutOf(built)

        assertEquals(Container.WEBM, layout.container)
        assertEquals("vp9", layout.codecs)
        assertEquals(1280 to 720, layout.width to layout.height)
        assertEquals(ByteSpan(0, built.cuesAt - 1), layout.init)
        assertEquals(ByteSpan(built.cuesAt, built.cuesAt + built.cuesLength - 1), layout.index)
        assertTrue(layout.isVideo)
        assertEquals("video/webm", layout.mimeType)
    }

    @Test
    fun `an Opus audio track reads its sample rate and channels and is typed audio`() {
        val built = withSeekHead(audioTrack("A_OPUS", 48000.0, 2))

        val layout = layoutOf(built)

        assertEquals("opus", layout.codecs)
        assertEquals(48000, layout.sampleRate)
        assertEquals(2, layout.channels)
        assertFalse(layout.isVideo)
        assertEquals("audio/webm", layout.mimeType)
        assertNull(layout.width)
    }

    @Test
    fun `a Vorbis track and a VP8 track are named for what they are`() {
        assertEquals("vorbis", layoutOf(withSeekHead(audioTrack("A_VORBIS", 44100.0, 2))).codecs)
        assertEquals("vp8", layoutOf(withSeekHead(videoTrack("V_VP8", 640, 360))).codecs)
    }

    @Test
    fun `an AV1 track builds its string from the codec private record`() {
        val av1c = byteArrayOf(0x81.toByte(), 0x08, 0x0C, 0x00)

        assertEquals("av01.0.08M.08", layoutOf(withSeekHead(videoTrack("V_AV1", 1920, 1080, av1c))).codecs)
        assertEquals("av01.0.08M.08", layoutOf(withSeekHead(videoTrack("V_AV1", 1920, 1080))).codecs)
    }

    @Test
    fun `cues that follow the tracks directly need no seek head`() {
        val tracks = videoTrack("V_VP9", 640, 360)
        val cues = element(Ebml.CUES, ByteArray(12))
        val segmentBody = tracks + cues
        val segmentHeader = idBytes(Ebml.SEGMENT) + sizeBytes(segmentBody.size)
        val file = ebmlHeader + segmentHeader + segmentBody

        val layout = WebmLayout.read(file, reader(file))

        val cuesAt = (ebmlHeader.size + segmentHeader.size + tracks.size).toLong()
        assertEquals(ByteSpan(cuesAt, cuesAt + cues.size - 1), layout.index)
        assertEquals(ByteSpan(0, cuesAt - 1), layout.init)
    }

    @Test
    fun `a codec a Cast receiver cannot play is refused by name`() {
        val failure = runCatching { layoutOf(withSeekHead(videoTrack("V_THEORA", 640, 360))) }.exceptionOrNull()

        assertTrue(failure is UnreadableLayout)
        assertTrue(failure!!.message, failure.message!!.contains("V_THEORA"))
    }

    @Test
    fun `a file with no cues cannot be seeked by range and says so`() {
        val tracks = videoTrack("V_VP9", 640, 360)
        val segmentHeader = idBytes(Ebml.SEGMENT) + sizeBytes(tracks.size)
        val file = ebmlHeader + segmentHeader + tracks

        val failure = runCatching { WebmLayout.read(file, reader(file)) }.exceptionOrNull()

        assertTrue(failure is UnreadableLayout)
        assertTrue(failure!!.message, failure.message!!.contains("Cues"))
    }

    @Test
    fun `a file with cues but no track entry says so`() {
        val cues = element(Ebml.CUES, ByteArray(12))
        val segmentHeader = idBytes(Ebml.SEGMENT) + sizeBytes(cues.size)
        val file = ebmlHeader + segmentHeader + cues

        val failure = runCatching { WebmLayout.read(file, reader(file)) }.exceptionOrNull()

        assertTrue(failure is UnreadableLayout)
        assertTrue(failure!!.message, failure.message!!.contains("track"))
    }

    @Test
    fun `a seek head that points at something other than cues is caught`() {
        val built = withSeekHead(videoTrack("V_VP9", 640, 360))
        val corrupt = built.file.copyOf().also { it[built.cuesAt.toInt()] = 0x1F }

        val failure = runCatching { WebmLayout.read(corrupt, reader(corrupt)) }.exceptionOrNull()

        assertTrue(failure is UnreadableLayout)
    }

    @Test
    fun `a file with no segment in its head is unreadable`() {
        val failure = runCatching { WebmLayout.read(ebmlHeader, reader(ebmlHeader)) }.exceptionOrNull()

        assertTrue(failure is UnreadableLayout)
        assertTrue(failure!!.message, failure.message!!.contains("Segment"))
    }

    @Test
    fun `probe tells a WebM from an MP4 from something else`() {
        val webm = withSeekHead(videoTrack("V_VP9", 640, 360)).file

        assertEquals("vp9", StreamLayouts.probe(reader(webm)).codecs)
        val junk = ByteArray(64) { 0x55 }
        val failure = runCatching { StreamLayouts.probe(reader(junk)) }.exceptionOrNull()
        assertTrue(failure is UnreadableLayout)
        assertTrue(failure!!.message, failure.message!!.contains("neither WebM nor MP4"))
    }

    @Test
    fun `variable-length integers read their marker, their value and an unknown size`() {
        assertEquals(0x1A45DFA3L, Ebml.id(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()), 0).value)
        assertEquals(5L, Ebml.size(byteArrayOf(0x85.toByte()), 0).value)
        assertEquals(300L, Ebml.size(byteArrayOf(0x41, 0x2C), 0).value)
        val unknown = Ebml.size(
            byteArrayOf(
                0x01,
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte(),
                0xFF.toByte()
            ),
            0
        )
        assertTrue(unknown.unknown)
        assertFalse(Ebml.size(byteArrayOf(0x81.toByte()), 0).unknown)
    }

    @Test
    fun `a length marker that is missing or runs off the end is an unreadable layout not a crash`() {
        listOf(byteArrayOf(0x00, 0x01), byteArrayOf(0x41), ByteArray(0)).forEach { bytes ->
            try {
                Ebml.size(bytes, 0)
                fail("expected an unreadable layout for ${bytes.toList()}")
            } catch (_: UnreadableLayout) {
            }
        }
    }

    @Test
    fun `floats and integers of the widths EBML uses are decoded`() {
        assertEquals(48000.0, Ebml.float(java.nio.ByteBuffer.allocate(4).putFloat(48000f).array(), 0, 4), 0.0)
        assertEquals(44100.0, Ebml.float(java.nio.ByteBuffer.allocate(8).putDouble(44100.0).array(), 0, 8), 0.0)
        assertEquals(0.0, Ebml.float(ByteArray(3), 0, 3), 0.0)
        assertEquals(0x0102L, Ebml.uint(byteArrayOf(1, 2), 0, 2))
    }
}
