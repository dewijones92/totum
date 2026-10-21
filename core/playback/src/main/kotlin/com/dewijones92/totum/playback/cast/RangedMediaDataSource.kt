package com.dewijones92.totum.playback.cast

import android.media.MediaDataSource
import com.dewijones92.totum.common.Diag

internal class RangedMediaDataSource(private val url: String, private val label: String) : MediaDataSource() {

    private val blocks = object : LinkedHashMap<Long, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }
    private var length = -1L
    private var fetches = 0
    private var fetchedBytes = 0L

    @Synchronized
    override fun getSize(): Long {
        if (length < 0) block(0)
        return length
    }

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (length in 0..position) return -1
        var done = 0
        var copied = -1
        while (done < size && copied != 0 && length !in 0..position + done) {
            copied = copyFrom(position + done, buffer, offset + done, size - done)
            done += copied
        }
        return if (done == 0) -1 else done
    }

    private fun copyFrom(at: Long, buffer: ByteArray, offset: Int, wanted: Int): Int {
        val start = at / BLOCK * BLOCK
        val block = block(start)
        val from = (at - start).toInt()
        val n = minOf(wanted, block.size - from).coerceAtLeast(0)
        if (n > 0) System.arraycopy(block, from, buffer, offset, n)
        return n
    }

    private fun block(start: Long): ByteArray = blocks[start] ?: fetch(start).also { blocks[start] = it }

    private fun fetch(start: Long): ByteArray {
        val began = System.currentTimeMillis()
        val reply = Upstream.get(url, "bytes=$start-${start + BLOCK - 1}")
        if (reply.failed) throw java.io.IOException("source answered ${reply.status} for bytes $start+")
        reply.totalLength?.let { length = it }
        val bytes = reply.body().use { it.readBytes() }
        if (length < 0 && reply.status == Http.OK) length = bytes.size.toLong()
        fetches++
        fetchedBytes += bytes.size
        if (fetches == 1 || fetches % LOG_EVERY == 0) {
            Diag.log(
                "cast",
                "source $label: fetch #$fetches at $start took ${System.currentTimeMillis() - began}ms, " +
                    "${fetchedBytes / Http.KB}KB so far of ${length / Http.KB}KB",
            )
        }
        return bytes
    }

    override fun close() {
        Diag.log("cast", "source $label closed after $fetches fetches, ${fetchedBytes / Http.KB}KB")
    }

    private companion object {
        const val BLOCK = 256L * 1024
        const val MAX_BLOCKS = 24
        const val LOG_EVERY = 20
    }
}
