package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalRangesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val content = ByteArray(100) { it.toByte() }

    private fun file(name: String) = tmp.newFile(name).apply { writeBytes(content) }

    private fun HttpReply.stream() = this as HttpReply.Stream

    private fun HttpReply.read(): ByteArray = stream().open().use { it.readBytes() }

    @Test
    fun `no range is a 200 of the whole file`() {
        val reply = LocalRanges.reply(file("a.webm"), null).stream()

        assertEquals(200, reply.status)
        assertEquals(100L, reply.length)
        assertEquals(content.toList(), reply.read().toList())
        assertEquals("bytes", reply.extra["Accept-Ranges"])
        assertTrue("Content-Range" !in reply.extra)
    }

    @Test
    fun `a closed range is a 206 of exactly those bytes with its Content-Range`() {
        val reply = LocalRanges.reply(file("a.webm"), "bytes=10-19").stream()

        assertEquals(206, reply.status)
        assertEquals(10L, reply.length)
        assertEquals("bytes 10-19/100", reply.extra["Content-Range"])
        assertEquals(content.copyOfRange(10, 20).toList(), reply.read().toList())
    }

    @Test
    fun `an open-ended range runs to the end`() {
        val reply = LocalRanges.reply(file("a.webm"), "bytes=90-").stream()

        assertEquals("bytes 90-99/100", reply.extra["Content-Range"])
        assertEquals(content.copyOfRange(90, 100).toList(), reply.read().toList())
    }

    @Test
    fun `a range past the end is clamped to the last byte`() {
        val reply = LocalRanges.reply(file("a.webm"), "bytes=95-5000").stream()

        assertEquals("bytes 95-99/100", reply.extra["Content-Range"])
        assertEquals(5L, reply.length)
        assertEquals(content.copyOfRange(95, 100).toList(), reply.read().toList())
    }

    @Test
    fun `only the first of several ranges is served`() {
        val reply = LocalRanges.reply(file("a.webm"), "bytes=0-3,10-12").stream()

        assertEquals("bytes 0-3/100", reply.extra["Content-Range"])
    }

    @Test
    fun `a suffix range is the last bytes of the file, not the first`() {
        val reply = LocalRanges.reply(file("a.mp4"), "bytes=-10").stream()

        assertEquals(206, reply.status)
        assertEquals("bytes 90-99/100", reply.extra["Content-Range"])
        assertEquals(content.copyOfRange(90, 100).toList(), reply.read().toList())
    }

    @Test
    fun `a stream of a known length is answered like a file, from whatever opens its bytes`() {
        val opened = mutableListOf<Pair<Long, Long>>()
        val whole = LocalRanges.replyFor(100, null, "video/mp4") { first, length ->
            opened += first to length
            content.copyOfRange(first.toInt(), (first + length).toInt()).inputStream()
        }.stream()
        val part = LocalRanges.replyFor(100, "bytes=20-29", "video/mp4") { first, length ->
            opened += first to length
            content.copyOfRange(first.toInt(), (first + length).toInt()).inputStream()
        }.stream()

        assertEquals(200, whole.status)
        assertEquals("video/mp4", whole.type)
        assertEquals(content.toList(), whole.read().toList())
        assertEquals(206, part.status)
        assertEquals("bytes 20-29/100", part.extra["Content-Range"])
        assertEquals(content.copyOfRange(20, 30).toList(), part.read().toList())
        assertEquals(listOf(0L to 100L, 20L to 10L), opened)
    }

    @Test
    fun `an mp4 is typed video mp4 and anything else video webm`() {
        assertEquals("video/mp4", LocalRanges.reply(file("a.mp4"), null).stream().type)
        assertEquals("video/webm", LocalRanges.reply(file("a.webm"), null).stream().type)
        assertEquals("video/webm", LocalRanges.reply(file("a.mkv"), null).stream().type)
    }

    @Test
    fun `reading a span returns those bytes and stops at the end of the file`() {
        val f = file("a.mp4")

        assertEquals(content.copyOfRange(4, 9).toList(), LocalRanges.bytes(f, ByteSpan(4, 8)).toList())
        assertEquals(content.copyOfRange(97, 100).toList(), LocalRanges.bytes(f, ByteSpan(97, 5000)).toList())
    }
}
