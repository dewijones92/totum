package com.dewijones92.totum.sabr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Building the request body that YouTube actually accepted.
 *
 * The expected bytes here are not invented: the same encoding, sent live on 2026-07-31,
 * returned 212KB of media. An empty body instead returns
 * `RELOAD_PLAYER_RESPONSE: sabr.malformed_config`.
 */
class VideoPlaybackAbrRequestTest {

    @Test
    fun `protobuf varints are seven bits per byte, unlike UMP's`() {
        assertArrayEquals(byteArrayOf(0x00), Protobuf.varint(0))
        assertArrayEquals(byteArrayOf(0x7F), Protobuf.varint(127))
        // 128 needs a continuation byte — where a UMP varint would still be one byte at 128.
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), Protobuf.varint(128))
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), Protobuf.varint(300))
    }

    @Test
    fun `the config goes in field 5 as a length-delimited value`() {
        val body = VideoPlaybackAbrRequest(byteArrayOf(1, 2, 3)).encode()

        // tag = (5 << 3) | 2 = 0x2A, then length 3, then the bytes — at the end, after state.
        assertTrue(
            "the config must be present as field 5",
            body.toList().windowed(5).any { it == listOf<Byte>(0x2A, 0x03, 1, 2, 3) },
        )
    }

    /**
     * `player_time_ms` has to sit inside `ClientAbrState` as field 28. The top-level field 4 is
     * IGNORED — measured 2026-07-31, four requests differing only in it returned byte-identical
     * responses, while moving it inside took the video from byte 1271335 to 8761825.
     */
    @Test
    fun `player time goes inside ClientAbrState, not at the top level`() {
        val body = VideoPlaybackAbrRequest(byteArrayOf(9), playerTimeMs = 30_000).encode()

        val state = Protobuf.read(body)[1]?.firstOrNull() as Protobuf.Value.Bytes
        assertEquals(30_000L, Protobuf.read(state.value)[28]?.let { (it.first() as Protobuf.Value.Number).value })
        assertNull("nothing may be written to the ignored top-level field 4", Protobuf.read(body)[4])
    }

    /**
     * A format needs its `xtags`: a real response carried 22 entries for itag 251, one per
     * dubbed language, and selecting without xtags made the server answer
     * `sabr.no_audio_selected`.
     */
    @Test
    fun `a preferred format carries itag, lastModified and xtags`() {
        val audio = SabrFormat(itag = 251, lastModified = 1_785_351_922_567_103L, xtags = "ChEKBWFjb250")

        val body = VideoPlaybackAbrRequest(byteArrayOf(9), audio = audio).encode()

        val field = Protobuf.read(body)[16]?.firstOrNull() as Protobuf.Value.Bytes
        val parsed = Protobuf.read(field.value)
        assertEquals(251L, (parsed[1]!!.first() as Protobuf.Value.Number).value)
        assertEquals(1_785_351_922_567_103L, (parsed[2]!!.first() as Protobuf.Value.Number).value)
        assertEquals("ChEKBWFjb250", (parsed[3]!!.first() as Protobuf.Value.Bytes).value.decodeToString())
    }

    @Test
    fun `audio and video go in fields 16 and 17, the ones the server honoured`() {
        val body = VideoPlaybackAbrRequest(
            byteArrayOf(9),
            audio = SabrFormat(251, 1L),
            video = SabrFormat(137, 2L),
        ).encode()

        val fields = Protobuf.read(body)
        assertNotNull("preferred_audio_format_ids", fields[16])
        assertNotNull("preferred_video_format_ids", fields[17])
        // selected_format_ids was measured to be ignored, so nothing is written to it.
        assertNull(fields[2])
    }

    /** Verified by probing: 1 returns audio alone; every other value tried sent video too. */
    @Test
    fun `audio-only is requested with the track bitfield set to one`() {
        val body = VideoPlaybackAbrRequest(byteArrayOf(9), tracks = SabrTracks.AUDIO_ONLY).encode()

        val state = Protobuf.read(body)[1]?.firstOrNull() as Protobuf.Value.Bytes
        assertEquals(1L, (Protobuf.read(state.value)[40]!!.first() as Protobuf.Value.Number).value)
    }

    @Test
    fun `a real-sized config encodes with a multi-byte length`() {
        val config = ByteArray(9_613) { it.toByte() }

        val body = VideoPlaybackAbrRequest(config).encode()

        val read = Protobuf.read(body)[5]?.firstOrNull() as Protobuf.Value.Bytes
        assertEquals(config.size, read.value.size)
    }

    private fun abrState(request: VideoPlaybackAbrRequest): Map<Int, List<Protobuf.Value>> =
        Protobuf.read((Protobuf.read(request.encode())[1]!!.first() as Protobuf.Value.Bytes).value)

    @Test
    fun `the playback rate is a float on the wire, as the schema says`() {
        // Wire type 5 for field 35, then 1.0f little-endian — the bytes SmartTube sends (2026-09-06).
        val request = VideoPlaybackAbrRequest(byteArrayOf(1)).encode()
        val body = (Protobuf.read(request)[1]!!.first() as Protobuf.Value.Bytes).value
        val expected = listOf(0x9D.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x80.toByte(), 0x3F.toByte())
        assertTrue(body.toList().windowed(expected.size).any { it == expected })
    }

    @Test
    fun `a sticky resolution goes in fields 16 and 21, and nowhere when unset`() {
        val with = abrState(VideoPlaybackAbrRequest(byteArrayOf(1), stickyResolution = 1080))
        assertEquals(Protobuf.Value.Number(1080), with[16]!!.first())
        assertEquals(Protobuf.Value.Number(1080), with[21]!!.first())
        val without = abrState(VideoPlaybackAbrRequest(byteArrayOf(1)))
        assertNull(without[16])
        assertNull(without[21])
    }
}
