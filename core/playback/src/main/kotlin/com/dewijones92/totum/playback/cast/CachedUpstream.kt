package com.dewijones92.totum.playback.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.ChunkedDataSource
import com.dewijones92.totum.playback.PlaybackCache
import com.dewijones92.totum.playback.homeServerAware
import java.io.IOException
import java.io.InputStream

@OptIn(markerClass = [UnstableApi::class])
internal fun cachedHttpFactory(context: Context): DataSource.Factory =
    PlaybackCache.factory(context, ChunkedDataSource.Factory(homeServerAware(DefaultDataSource.Factory(context))))

@OptIn(markerClass = [UnstableApi::class])
internal class CachedUpstream(private val factory: DataSource.Factory) {

    private class Ask(val position: Long, val length: Long, val ranged: Boolean)

    fun get(url: String, range: String?): UpstreamReply {
        val ask = askFor(RangeAsk.parse(range))
        val spec = DataSpec.Builder().setUri(url.toUri()).setPosition(ask.position).setLength(ask.length).build()
        val source = factory.createDataSource()
        val opened = try {
            source.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            runCatching { source.close() }
            Diag.warn("cast", "the cache-backed relay got ${e.responseCode} from the source for range=$range")
            return UpstreamReply(e.responseCode, "text/plain", null, 0L) { ByteArray(0).inputStream() }
        } catch (e: IOException) {
            runCatching { source.close() }
            throw e
        }
        return replyOf(url, ask, source, opened)
    }

    private fun askFor(ask: RangeAsk): Ask = when (ask) {
        is RangeAsk.Closed -> Ask(ask.first, ask.bytes, ranged = true)
        is RangeAsk.Open -> Ask(ask.first, C.LENGTH_UNSET.toLong(), ranged = true)
        else -> Ask(0L, C.LENGTH_UNSET.toLong(), ranged = false)
    }

    private fun replyOf(url: String, ask: Ask, source: DataSource, opened: Long): UpstreamReply {
        val bytes = opened.takeIf { it != C.LENGTH_UNSET.toLong() }
        val total = url.substringAfter("clen=", "").substringBefore('&').toLongOrNull()
        val last = bytes?.let { ask.position + it - 1 } ?: total?.let { it - 1 }
        val contentRange = if (ask.ranged && last != null) "bytes ${ask.position}-$last/${total ?: "*"}" else null
        return UpstreamReply(
            status = if (ask.ranged) PARTIAL else OK,
            contentType = source.responseHeaders["Content-Type"]?.firstOrNull() ?: mimeOf(url),
            contentRange = contentRange,
            contentLength = bytes,
        ) { Reading(source) }
    }

    private fun mimeOf(url: String): String =
        url.substringAfter("mime=", "").substringBefore('&').replace("%2F", "/", ignoreCase = true)
            .ifEmpty { "application/octet-stream" }

    private class Reading(private val source: DataSource) : InputStream() {
        private var closed = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val got = source.read(buffer, off, len)
            return if (got == C.RESULT_END_OF_INPUT) -1 else got
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { source.close() }
        }
    }

    private companion object {
        const val OK = 200
        const val PARTIAL = 206
        const val BYTE_MASK = 0xff
    }
}
