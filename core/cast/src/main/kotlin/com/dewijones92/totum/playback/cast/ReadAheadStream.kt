package com.dewijones92.totum.playback.cast

import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal class ReadAheadStream(
    private val source: InputStream,
    bufferBytes: Int,
    pool: Executor,
    private val giveUpAfterMs: Long = GIVE_UP_MS,
    private val onSourceEnded: () -> Unit = {},
) : InputStream() {

    private class Chunk(val bytes: ByteArray, val size: Int)

    private class Failure(val error: IOException)

    private object End

    private val queue = ArrayBlockingQueue<Any>((bufferBytes / CHUNK_BYTES).coerceAtLeast(MIN_CHUNKS))

    @Volatile private var closed = false

    private var current: Chunk? = null
    private var offset = 0
    private var finished = false

    init {
        pool.execute { produce() }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun produce() {
        try {
            while (!closed) {
                val buffer = ByteArray(CHUNK_BYTES)
                val got = source.read(buffer)
                if (got < 0) {
                    runCatching(onSourceEnded)
                    put(End)
                    return
                }
                if (got > 0 && !put(Chunk(buffer, got))) return
            }
        } catch (e: IOException) {
            put(Failure(e))
        } catch (e: RuntimeException) {
            put(Failure(IOException("the source failed: ${e.message}", e)))
        } finally {
            runCatching { source.close() }
        }
    }

    private fun put(item: Any): Boolean {
        while (!closed) {
            if (queue.offer(item, POLL_MS, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (finished) return -1
        val chunk = current?.takeIf { offset < it.size } ?: nextChunk() ?: return -1
        val n = minOf(len, chunk.size - offset)
        System.arraycopy(chunk.bytes, offset, buffer, off, n)
        offset += n
        return n
    }

    private fun awaitItem(): Any {
        val item = try {
            queue.poll(giveUpAfterMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("interrupted while waiting for the source: ${e.message}")
        }
        return item ?: throw IOException("the source gave nothing for ${giveUpAfterMs / MS_PER_S}s")
    }

    private fun nextChunk(): Chunk? {
        return when (val item = awaitItem()) {
            is Chunk -> item.also {
                current = it
                offset = 0
            }
            is Failure -> {
                finished = true
                throw item.error
            }
            else -> {
                finished = true
                null
            }
        }
    }

    override fun available(): Int = current?.let { it.size - offset } ?: 0

    override fun close() {
        closed = true
        queue.clear()
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
        const val MIN_CHUNKS = 2
        const val POLL_MS = 100L
        const val GIVE_UP_MS = 60_000L
        const val MS_PER_S = 1_000L
        const val BYTE_MASK = 0xff
    }
}
