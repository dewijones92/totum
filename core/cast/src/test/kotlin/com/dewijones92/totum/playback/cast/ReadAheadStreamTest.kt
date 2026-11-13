package com.dewijones92.totum.playback.cast

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class ReadAheadStreamTest {

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "read-ahead-test").apply { isDaemon = true } }

    @After
    fun tidy() {
        pool.shutdownNow()
    }

    private class Counting(private val inner: InputStream) : InputStream() {
        val read = AtomicLong()
        val closed = AtomicBoolean()
        override fun read(): Int = inner.read().also { if (it >= 0) read.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) read.addAndGet(it.toLong()) }

        override fun close() {
            closed.set(true)
            inner.close()
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + WAIT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < until) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `the source is read ahead while nobody is reading, and every byte arrives in order`() {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val source = Counting(ByteArrayInputStream(data))
        val stream = ReadAheadStream(source, bufferBytes = 1_000_000, pool = pool)

        waitFor("the source to be read ahead") { source.read.get() == data.size.toLong() }
        val got = stream.readBytes()

        assertEquals(data.toList(), got.toList())
    }

    @Test
    fun `read-ahead stops at its bound until the reader catches up`() {
        val source = Counting(ByteArrayInputStream(ByteArray(2_000_000)))
        val stream = ReadAheadStream(source, bufferBytes = 256 * 1024, pool = pool)
        Thread.sleep(300)

        val ahead = source.read.get()

        assertTrue("read $ahead bytes ahead of a 256KB bound", ahead < 512 * 1024)
        assertEquals(2_000_000, stream.readBytes().size)
    }

    @Test
    fun `a source that fails gives the reader the bytes before it and then the same error`() {
        val source = object : InputStream() {
            var sent = false
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!sent) {
                    sent = true
                    b.fill(7, off, off + 10)
                    return 10
                }
                throw IOException("connection reset")
            }
        }
        val stream = ReadAheadStream(source, bufferBytes = 100_000, pool = pool)
        val buffer = ByteArray(100)

        assertEquals(10, stream.read(buffer, 0, 100))
        val failure = runCatching { stream.read(buffer, 0, 100) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("connection reset", failure?.message)
    }

    @Test
    fun `closing it lets the source go`() {
        val source = Counting(ByteArrayInputStream(ByteArray(5_000_000)))
        val stream = ReadAheadStream(source, bufferBytes = 128 * 1024, pool = pool)
        Thread.sleep(100)

        stream.close()

        waitFor("the source to be closed") { source.closed.get() }
    }

    @Test
    fun `the end of the source is announced once, when it has all been read`() {
        val ended = AtomicLong()
        ReadAheadStream(
            ByteArrayInputStream(ByteArray(1_000)),
            100_000,
            pool,
            onSourceEnded = { ended.incrementAndGet() }
        )

        waitFor("the end") { ended.get() > 0 }
        Thread.sleep(100)

        assertEquals(1, ended.get())
    }

    private companion object {
        const val WAIT_MS = 5_000L
    }
}
