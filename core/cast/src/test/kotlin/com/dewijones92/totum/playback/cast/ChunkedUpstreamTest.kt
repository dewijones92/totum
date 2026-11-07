package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.YoungStreamUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class ChunkedUpstreamTest {

    private val content = ByteArray(1_000) { (it % 251).toByte() }
    private val seen = mutableListOf<String?>()
    private var server: CastHttpServer? = null

    @After
    fun tidy() {
        server?.stop()
    }

    private fun upstream(handle: (HttpRequest) -> HttpReply): String {
        val s = CastHttpServer({
            synchronized(seen) { seen += it.header("range") }
            handle(it)
        }, InetAddress.getLoopbackAddress())
        server = s
        return "http://127.0.0.1:${s.start(0)}/videoplayback?clen=${content.size}"
    }

    private fun onlyRanges(cutPlainGetAt: Int = 300): (HttpRequest) -> HttpReply = { request ->
        val range = request.header("range")
        if (range == null) {
            HttpReply.Stream(Http.OK, "video/mp4", content.size.toLong(), emptyMap()) {
                ByteArrayInputStream(content, 0, cutPlainGetAt)
            }
        } else {
            val (first, last) = range.removePrefix("bytes=").split('-').map { it.toLong() }
            val end = minOf(last, content.size - 1L)
            HttpReply.Stream(
                Http.PARTIAL,
                "video/mp4",
                end - first + 1,
                mapOf("Content-Range" to "bytes $first-$end/${content.size}"),
            ) { ByteArrayInputStream(content, first.toInt(), (end - first + 1).toInt()) }
        }
    }

    private fun fixed(url: String, first: Long, length: Long, chunk: Long = 128) =
        ChunkedUpstream(url, first, length, chunk)

    @Test
    fun `the fake source cuts a plain GET short, so only ranges can fetch the whole file`() {
        val url = upstream(onlyRanges())

        val connection = URL(url).openConnection() as HttpURLConnection
        val got = runCatching { connection.inputStream.readBytes() }

        val short = got.isFailure || got.getOrThrow().size < content.size
        assertTrue("a plain GET must fail or fall short: ${got.getOrNull()?.size}", short)
    }

    @Test
    fun `a source that serves only ranges is read whole, in bounded ranges, never by a plain GET`() {
        val url = upstream(onlyRanges())

        val got = fixed(url, 0, content.size.toLong()).use { it.readBytes() }

        assertEquals(content.toList(), got.toList())
        assertTrue(seen.toString(), seen.none { it == null })
        assertEquals(
            (0 until 8).map { i -> "bytes=${i * 128}-${minOf(i * 128 + 127, 999)}" },
            seen.toList(),
        )
    }

    @Test
    fun `reading from the middle serves exactly the asked bytes`() {
        val url = upstream(onlyRanges())

        val got = fixed(url, 250, 400).use { it.readBytes() }

        assertEquals(content.copyOfRange(250, 650).toList(), got.toList())
        assertEquals("bytes=250-377", seen.first())
        assertTrue(seen.toString(), seen.size == 4)
    }

    @Test
    fun `single bytes and small buffers read the same bytes as one big read`() {
        val url = upstream(onlyRanges())
        val stream = fixed(url, 0, 300)

        val first = stream.read()
        val buffer = ByteArray(7)
        val n = stream.read(buffer, 0, buffer.size)

        assertEquals(content[0].toInt() and 0xff, first)
        assertEquals(7, n)
        assertEquals(content.copyOfRange(1, 8).toList(), buffer.toList())
        stream.close()
    }

    @Test
    fun `a refusal part way through is an error the caller sees, not a short file`() {
        var asked = 0
        val url = upstream { request ->
            if (++asked > 2) HttpReply.Bytes(403, "text/plain", ByteArray(0)) else onlyRanges()(request)
        }

        val failure = runCatching { fixed(url, 0, content.size.toLong()).use { it.readBytes() } }.exceptionOrNull()

        assertTrue(failure.toString(), failure is IOException)
        assertTrue(failure.toString(), failure!!.message!!.contains("403"))
    }

    @Test
    fun `a source that answers 200 to a range is an error, not a silent whole file`() {
        val url = upstream { HttpReply.Bytes(Http.OK, "video/mp4", content) }

        val failure = runCatching { fixed(url, 0, 10).use { it.readBytes() } }.exceptionOrNull()

        assertTrue(failure.toString(), failure is IOException)
    }

    @Test
    fun `a source that ends while bytes are still owed is an error, not a silent short file`() {
        val url = upstream { request ->
            val first = request.header("range")!!.removePrefix("bytes=").substringBefore('-').toLong()
            if (first >= 256) {
                HttpReply.Bytes(Http.PARTIAL, "video/mp4", ByteArray(0), mapOf("Content-Range" to "bytes */1000"))
            } else {
                onlyRanges()(request)
            }
        }

        val failure = runCatching { fixed(url, 0, content.size.toLong()).use { it.readBytes() } }.exceptionOrNull()

        assertTrue(failure.toString(), failure is IOException)
    }

    @Test
    fun `a young URL refused once is asked again when YouTube should accept it, and then read whole`() {
        var now = 1_000_000_000L
        val sleeps = mutableListOf<Long>()
        var refusals = 0
        val url = upstream { request ->
            if (refusals++ < 2) HttpReply.Bytes(403, "text/plain", ByteArray(0)) else onlyRanges()(request)
        }
        val young = "$url&expire=${(now + YoungStreamUrl.LEASE_MS) / 1_000}"
        val patient = PatientUpstream(clock = { now }, sleep = {
            sleeps += it
            now += it
        })

        val got = ChunkedUpstream(young, 0, content.size.toLong(), 128, patient::get).use { it.readBytes() }

        assertEquals(content.toList(), got.toList())
        assertEquals(2, sleeps.size)
        assertTrue(sleeps.toString(), sleeps.all { it > 0 })
    }

    @Test
    fun `a URL that is not young is not waited for, its refusal is the answer`() {
        val now = 1_000_000_000L
        val sleeps = mutableListOf<Long>()
        val url = upstream { HttpReply.Bytes(403, "text/plain", ByteArray(0)) }
        val old = "$url&expire=${(now + YoungStreamUrl.LEASE_MS) / 1_000 - 3_600}"
        val patient = PatientUpstream(clock = { now }, sleep = { sleeps += it })

        val reply = patient.get(old, "bytes=0-9")

        assertEquals(403, reply.status)
        assertTrue(sleeps.isEmpty())
    }
}
