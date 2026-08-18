package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Streams a direct media URL (podcast enclosure) to a file, emitting
 * progress. Used for anything with a ready-to-fetch media URL.
 */
public class HttpDownloadStrategy(private val client: OkHttpClient) : DownloadStrategy {

    // audioOnly is moot here: a podcast enclosure is the audio.
    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> = flow {
        val url = item.fetchUrl
        if (url == null) {
            emit(DownloadState.Failed("Nothing to download"))
            return@flow
        }
        try {
            client.newCall(Request.Builder().url(url.value).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(DownloadState.Failed("HTTP ${response.code}"))
                    return@flow
                }
                val type = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                if (type != null && type in CANNOT_BE_MEDIA) {
                    // Removed like the IOException branch does. The caller creates the target before
                    // handing it over, so leaving it behind would leave a zero-length file where a
                    // downloaded episode is meant to be.
                    target.delete()
                    emit(DownloadState.Failed("the server sent $type, which is not media"))
                    return@flow
                }
                val body = response.body
                val total = body.contentLength().takeIf { it > 0 }
                var downloaded = 0L
                var lastEmitted = 0L
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastEmitted >= EMIT_EVERY_BYTES) {
                                lastEmitted = downloaded
                                emit(DownloadState.Downloading(downloaded, total))
                            }
                        }
                    }
                }
                emit(DownloadState.Downloaded(target.absolutePath))
            }
        } catch (e: IOException) {
            target.delete()
            emit(DownloadState.Failed(e.message ?: "network error"))
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val EMIT_EVERY_BYTES = 256 * 1024L

        /**
         * Content types that certainly are not an episode, so a 200 carrying one is a failure.
         *
         * A REJECT list, not an allow-list, and that direction is the whole design. Real enclosures
         * carry `audio/mpeg`, `audio/mp4`, `video/mp4` and very commonly `application/octet-stream`, and
         * plenty of servers state nothing at all — so allow-listing "media" would break working feeds,
         * which is a worse failure than the one this fixes. Only the types that cannot possibly play are
         * named.
         *
         * Without this, a moved feed's HTML page, a paywall interstitial or a CDN error page was written
         * to disk and recorded `Downloaded`. That is not merely a bad file: `routeNow` then prefers the
         * copy on disk, Media3 throws `UnrecognizedInputFormatException` (an `IOException`, so recovery
         * calls it `Unreachable`), the retries replay the same file, `playWithoutTheStream` re-routes to
         * it, returns true and resets the budget — so the item never plays AND the queue never advances
         * past it, behind a green tick. Only deleting the download escapes.
         */
        val CANNOT_BE_MEDIA: Set<String> = setOf(
            "text/html",
            "application/xhtml+xml",
            "text/xml",
            "application/xml",
            "application/rss+xml",
            "application/json",
        )
    }
}
