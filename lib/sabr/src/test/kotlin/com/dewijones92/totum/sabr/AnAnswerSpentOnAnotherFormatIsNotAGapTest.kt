package com.dewijones92.totum.sabr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An answer that carried ANOTHER format's media and none of ours is re-asked at the same time, not
 * skipped past.
 *
 * Measured 2026-09-07 (Spring, itag 400 beside audio 251): one such skip at 5.4s tore a hole to 35s
 * that 20MB of later media never filled, and the player starved at byte 958854 while the stream held
 * everything after 35s.
 */
class AnAnswerSpentOnAnotherFormatIsNotAGapTest {

    private val video = SabrFormat(itag = 400, lastModified = 42L, xtags = null)
    private val audio = SabrFormat(itag = 251, lastModified = 43L, xtags = "orig")

    private fun stream(transport: SabrTransport) = SabrStream(
        url = "https://example.test/videoplayback",
        ustreamerConfig = byteArrayOf(1),
        format = video,
        kind = SabrTrackKind.VIDEO,
        transport = transport,
        totalBytes = 4L * CHUNK,
        durationMs = 40_000L,
    )

    private fun segment(format: SabrFormat, id: Int, index: Int): ByteArray =
        UmpFraming.mediaHeader(
            id = id,
            format = format,
            offset = index.toLong() * CHUNK,
            length = CHUNK.toInt(),
            startMs = index * 10_000L,
            durationMs = 10_000L,
        ) + UmpFraming.media(id, ByteArray(CHUNK.toInt()) { 5 })

    @Test
    fun `an answer carrying only the other format re-asks the same time, and the next answer fills it`() = runTest {
        val server = FakeSabrServer(listOf(segment(video, 0, 0), segment(audio, 1, 1), segment(video, 0, 1)))
        val stream = stream(server)
        stream.read(from = 0)
        val second = stream.read(from = CHUNK)

        assertEquals("the second video segment arrived on the third answer", CHUNK.toInt(), second.size)
        val asked = server.requests.map(::playerTimeMsIn)
        assertEquals("the audio-only answer did not move the question", asked[1], asked[2])
        assertTrue("never skipped thirty seconds ahead", asked.all { it < 30_000 })
    }

    @Test
    fun `the same-time budget is finite, so a server that only ever sends audio still moves on`() = runTest {
        val server = FakeSabrServer { at -> if (at == 0) segment(video, 0, 0) else segment(audio, 1, at) }
        val stream = stream(server)
        stream.read(from = 0)
        stream.read(from = CHUNK)
        val asked = server.requests.map(::playerTimeMsIn)
        assertTrue("eventually the skip rule takes over: $asked", asked.last() > asked[1])
    }

    private companion object {
        const val CHUNK = 1024L
    }
}
