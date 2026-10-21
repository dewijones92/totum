package com.dewijones92.totum.playback.cast

import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile

internal object LocalRanges {

    fun bytes(file: File, span: ByteSpan): ByteArray = RandomAccessFile(file, "r").use { f ->
        val last = minOf(span.last, f.length() - 1)
        ByteArray((last - span.first + 1).toInt().coerceAtLeast(0)).also {
            f.seek(span.first)
            f.readFully(it)
        }
    }

    fun reply(file: File, range: String?): HttpReply {
        val total = file.length()
        val (first, last) = parse(range, total)
        val length = last - first + 1
        val extra = buildMap {
            put("Accept-Ranges", "bytes")
            if (range != null) put("Content-Range", "bytes $first-$last/$total")
        }
        val type = if (file.extension == "mp4") "video/mp4" else "video/webm"
        return HttpReply.Stream(if (range != null) Http.PARTIAL else Http.OK, type, length, extra) {
            Bounded(FileInputStream(file).apply { skip(first) }, length)
        }
    }

    private fun parse(range: String?, total: Long): Pair<Long, Long> {
        val spec = range?.substringAfter("bytes=", "")?.substringBefore(',').orEmpty()
        val first = spec.substringBefore('-').toLongOrNull() ?: 0L
        val last = spec.substringAfter('-', "").toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
        return first.coerceIn(0, total - 1) to last
    }

    private class Bounded(private val inner: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int = if (left <= 0) -1 else inner.read().also { if (it >= 0) left-- }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = inner.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }

        override fun close() = inner.close()
    }
}
