package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Collections
import java.util.concurrent.Executors

class VideoRelayTest {

    private val content = ByteArray(6_000) { (it % 251).toByte() }
    private val calls: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private val lines: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var upstreamDelayMs = 0L
    private var failRangeStartingAt: Long? = null
    private val answered = java.util.concurrent.atomic.AtomicInteger()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay-test").apply { isDaemon = true } }

    private val fake: (String, String?) -> UpstreamReply = { _, range ->
        calls += range
        if (upstreamDelayMs > 0) Thread.sleep(upstreamDelayMs)
        val ask = RangeAsk.parse(range)
        val first = when (ask) {
            is RangeAsk.Closed -> ask.first
            is RangeAsk.Open -> ask.first
            else -> 0L
        }
        if (failRangeStartingAt == first) throw IOException("the source dropped the connection")
        val last = when (ask) {
            is RangeAsk.Closed -> minOf(ask.last, content.size - 1L)
            else -> content.size - 1L
        }
        val slice = content.copyOfRange(first.toInt(), last.toInt() + 1)
        answered.incrementAndGet()
        UpstreamReply(PARTIAL, "video/webm", "bytes $first-$last/${content.size}", slice.size.toLong()) {
            ByteArrayInputStream(slice)
        }
    }

    private fun relay(
        limits: RelayLimits = RelayLimits(prefetchMinBytes = 0, waitForPrefetchMs = 3_000),
    ) = VideoRelay(
        fetch = fake,
        log = RelayLog("test", sink = { lines += it }),
        pool = pool,
        limits = limits,
        note = { lines += "note: $it" },
    )

    private fun HttpReply.readAll(): ByteArray = when (this) {
        is HttpReply.Bytes -> body
        is HttpReply.Stream -> open().use { it.readBytes() }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what; calls=$calls lines=$lines" }
            Thread.sleep(10)
        }
    }

    @After
    fun tidy() {
        pool.shutdownNow()
    }

    @Test
    fun `the range after the one the TV is reading is fetched meanwhile, so the next request is served from memory`() {
        upstreamDelayMs = 300
        val r = relay()

        val first = r.serve("u", "bytes=0-999").readAll()
        waitFor("the read-ahead of the next range") { answered.get() == 2 }
        Thread.sleep(50)
        val asked = System.currentTimeMillis()
        val second = r.serve("u", "bytes=1000-1999")
        val took = System.currentTimeMillis() - asked

        assertTrue(second is HttpReply.Bytes)
        assertEquals(content.copyOfRange(0, 1000).toList(), first.toList())
        assertEquals(content.copyOfRange(1000, 2000).toList(), second.readAll().toList())
        assertTrue("served in ${took}ms, which is not from memory", took < 150)
        assertEquals(listOf("bytes=0-999", "bytes=1000-1999"), calls.take(2))
        assertEquals(1, calls.count { it == "bytes=1000-1999" })
        r.close()
    }

    @Test
    fun `control, with no read-ahead the same second request waits for the slow source`() {
        upstreamDelayMs = 300
        val r = relay(RelayLimits(prefetchMinBytes = Long.MAX_VALUE))

        r.serve("u", "bytes=0-999").readAll()
        Thread.sleep(400)
        val asked = System.currentTimeMillis()
        val second = r.serve("u", "bytes=1000-1999")
        val took = System.currentTimeMillis() - asked

        assertTrue(second is HttpReply.Stream)
        assertTrue("took only ${took}ms, so the delay is not what the other test measures", took >= 250)
        assertEquals(listOf<String?>("bytes=0-999", "bytes=1000-1999"), calls.toList())
        r.close()
    }

    @Test
    fun `a memory reply says which bytes it holds and how long the whole is`() {
        val r = relay()
        r.serve("u", "bytes=0-999").readAll()
        waitFor("the read-ahead") { answered.get() == 2 }
        Thread.sleep(50)

        val second = r.serve("u", "bytes=1000-1999") as HttpReply.Bytes

        assertEquals(206, second.status)
        assertEquals("bytes 1000-1999/${content.size}", second.extra["Content-Range"])
        assertEquals("bytes", second.extra["Accept-Ranges"])
        assertEquals("video/webm", second.type)
        r.close()
    }

    @Test
    fun `a seek to somewhere that was not read ahead goes to the source`() {
        val r = relay()
        r.serve("u", "bytes=0-999").readAll()
        waitFor("the read-ahead") { calls.size == 2 }

        val seek = r.serve("u", "bytes=3000-3999")

        assertTrue(seek is HttpReply.Stream)
        assertEquals(content.copyOfRange(3000, 4000).toList(), seek.readAll().toList())
        assertTrue(calls.toString(), calls.contains("bytes=3000-3999"))
        r.close()
    }

    @Test
    fun `a small range is not read ahead, so the index and header fetches do not drag a segment behind them`() {
        val r = relay(RelayLimits(prefetchMinBytes = 4_096))

        r.serve("u", "bytes=0-999").readAll()
        Thread.sleep(200)

        assertEquals(listOf<String?>("bytes=0-999"), calls.toList())
        r.close()
    }

    @Test
    fun `an open-ended or unranged request is relayed as it is and reads nothing ahead`() {
        val r = relay()

        val open = r.serve("u", "bytes=2000-").readAll()
        val whole = r.serve("u", null).readAll()
        Thread.sleep(200)

        assertEquals(content.copyOfRange(2000, content.size).toList(), open.toList())
        assertEquals(content.toList(), whole.toList())
        assertEquals(listOf<String?>("bytes=2000-", null), calls.toList())
        r.close()
    }

    @Test
    fun `nothing is read ahead past the end of the file`() {
        val r = relay()

        r.serve("u", "bytes=5000-5999").readAll()
        Thread.sleep(200)

        assertEquals(listOf<String?>("bytes=5000-5999"), calls.toList())
        r.close()
    }

    @Test
    fun `read-ahead stays under the memory bound`() {
        val r = relay(RelayLimits(prefetchMinBytes = 0, heldMaxBytes = 1_500, waitForPrefetchMs = 3_000))

        r.serve("u", "bytes=0-999").readAll()
        waitFor("the first read-ahead") { calls.size == 2 }
        r.serve("v", "bytes=0-999").readAll()
        waitFor("the second") { calls.size >= 4 }

        assertTrue("held ${r.heldBytes()} bytes", r.heldBytes() <= 1_500)
        r.close()
    }

    @Test
    fun `a read-ahead that failed falls back to asking the source, and says so`() {
        val r = relay()
        failRangeStartingAt = 1000
        r.serve("u", "bytes=0-999").readAll()
        waitFor("the failed read-ahead") { calls.size == 2 }
        failRangeStartingAt = null

        val second = r.serve("u", "bytes=1000-1999")

        assertTrue(second is HttpReply.Stream)
        assertEquals(content.copyOfRange(1000, 2000).toList(), second.readAll().toList())
        assertTrue(lines.toString(), lines.any { it.startsWith("note: read-ahead of 1000-1999 failed") })
        r.close()
    }

    @Test
    fun `every video request is logged with its range, first byte, bytes, time, speed and whether it was aborted`() {
        val r = relay()

        r.serve("u", "bytes=0-999").readAll()

        val line = lines.single { it.contains("video request #1") }
        assertTrue(line, line.contains("range=bytes=0-999"))
        assertTrue(line, line.contains("first byte in"))
        assertTrue(line, line.contains("1KB") || line.contains("0KB"))
        assertTrue(line, line.contains("Mbit/s") || line.contains("n/a"))
        assertTrue(line, line.contains("aborted=no"))
        r.close()
    }

    @Test
    fun `a request the TV gave up on is logged as aborted`() {
        val r = relay(RelayLimits(prefetchMinBytes = Long.MAX_VALUE))
        val reply = r.serve("u", "bytes=0-4999") as HttpReply.Stream

        reply.open().use { it.read(ByteArray(100)) }

        assertTrue(lines.toString(), lines.any { it.contains("aborted=yes") })
        r.close()
    }

    @Test
    fun `a HEAD asks the source but starts no read-ahead and leaves nothing running`() {
        val r = relay()

        val reply = r.serve("u", "bytes=0-999", head = true)
        Thread.sleep(200)

        assertEquals(206, (reply as HttpReply.Stream).status)
        assertEquals(1, calls.size)
        r.close()
    }

    @Test
    fun `a source that fails to answer is an error for the TV and is counted as aborted`() {
        val r = VideoRelay(
            fetch = { _, _ -> throw IOException("no route") },
            log = RelayLog("test", sink = { lines += it }),
            pool = pool,
        )

        val failure = runCatching { r.serve("u", "bytes=0-999") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(lines.toString(), lines.any { it.contains("aborted=yes") })
        assertFalse(lines.any { it.contains("note:") })
    }

    private companion object {
        const val PARTIAL = 206
        const val WAIT_MS = 5_000L
    }
}
