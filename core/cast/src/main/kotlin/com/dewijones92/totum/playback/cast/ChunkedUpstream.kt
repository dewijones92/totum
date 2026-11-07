package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.ChunkedRead
import com.dewijones92.totum.playback.UNKNOWN_LENGTH
import java.io.IOException
import java.io.InputStream

public class ChunkedUpstream(
    private val url: String,
    private val firstByte: Long,
    length: Long,
    chunkBytes: Long = DEFAULT_CHUNK_BYTES,
    private val get: (String, String?) -> UpstreamReply = PatientUpstream()::get,
) : InputStream() {

    private val cursor = ChunkedRead(length, chunkBytes)
    private var body: InputStream? = null
    private var ranges = 0

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (!cursor.finished) {
            if (!cursor.rangeOpen) openNextRange()
            val room = cursor.cap(length)
            val got = if (room > 0) body?.read(buffer, offset, room) ?: -1 else -1
            if (got >= 0) {
                cursor.served(got)
                if (!cursor.rangeOpen) closeBody()
                return got
            }
            closeBody()
            when (cursor.endOfRange()) {
                ChunkedRead.RangeEnd.Continue -> Unit
                ChunkedRead.RangeEnd.Ended -> return -1
                ChunkedRead.RangeEnd.EndedEarly -> {
                    Diag.warn("cast", "relayed stream ended while it still owed bytes: ${cursor.describe()}")
                    throw IOException("the source ended while bytes were still owed (${cursor.describe()})")
                }
            }
        }
        return -1
    }

    private fun openNextRange() {
        val range = cursor.nextRange()
        val first = firstByte + range.offset
        val last = first + range.bytes - 1
        val reply = get(url, "bytes=$first-$last")
        ranges++
        if (reply.status != Http.PARTIAL) {
            val status = reply.status
            runCatching { reply.body().close() }
            throw IOException("the source answered $status, not 206, for bytes $first-$last (range $ranges)")
        }
        cursor.opened(reply.contentLength ?: UNKNOWN_LENGTH)
        body = reply.body()
    }

    private fun closeBody() {
        runCatching { body?.close() }
        body = null
    }

    override fun close() {
        closeBody()
    }

    public companion object {
        public const val DEFAULT_CHUNK_BYTES: Long = 2L * 1024 * 1024
        private const val BYTE_MASK = 0xff
    }
}
