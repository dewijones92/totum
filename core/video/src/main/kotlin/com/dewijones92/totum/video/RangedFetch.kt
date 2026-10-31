package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

class RangedFetch(private val client: OkHttpClient, private val chunkBytes: Long = CHUNK_BYTES) {

    /** Null when [into] holds the whole stream, else why not. */
    suspend fun fetch(url: HttpUrl, into: File, onBytes: suspend (Long) -> Unit): String? {
        val total = lengthOf(url) ?: try {
            askedLength(url)
        } catch (e: IOException) {
            return "could not ask the server for the length: $e"
        } ?: return "neither the link nor the server states a length"
        into.parentFile?.mkdirs()
        val failure = try {
            writeAll(url, total, into, onBytes)
        } catch (e: IOException) {
            "stopped at byte ${into.length()} of $total: $e"
        }
        return failure ?: if (into.length() == total) null else "got ${into.length()} of $total bytes"
    }

    private suspend fun writeAll(url: HttpUrl, total: Long, into: File, onBytes: suspend (Long) -> Unit): String? =
        into.outputStream().use { out ->
            var written = 0L
            while (written < total) {
                val end = minOf(written + chunkBytes, total) - 1
                copyRange(url, written, end, out)?.let { return@use "$it at byte $written of $total" }
                written = into.length()
                onBytes(written)
            }
            null
        }

    private fun askedLength(url: HttpUrl): Long? {
        val request = Request.Builder().url(url.value).header("Range", "bytes=0-0").build()
        return client.newCall(request).execute().use { response ->
            response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        }
    }

    private fun copyRange(url: HttpUrl, from: Long, to: Long, out: java.io.OutputStream): String? {
        val request = Request.Builder().url(url.value).header("Range", "bytes=$from-$to").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return "HTTP ${response.code}"
            response.body.byteStream().use { it.copyTo(out, BUFFER_BYTES) }
        }
        out.flush()
        return null
    }

    companion object {
        private const val CHUNK_BYTES = 10L * 1024 * 1024
        private const val BUFFER_BYTES = 64 * 1024

        fun lengthOf(url: HttpUrl): Long? = Regex(
            """[?&]clen=(\d+)"""
        ).find(url.value)?.groupValues?.get(1)?.toLongOrNull()
    }
}
