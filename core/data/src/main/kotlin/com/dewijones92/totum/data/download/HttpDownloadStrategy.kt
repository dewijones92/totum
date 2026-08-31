package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Streams a direct media URL (podcast enclosure) to a file, emitting
 * progress. Used for anything with a ready-to-fetch media URL.
 *
 * Bytes land in a `.part` beside the target and are only moved into place once the fetch is
 * complete, so a partly-fetched file can never be mistaken for a download — and can be **resumed**
 * next time (see [resumable]).
 */
public class HttpDownloadStrategy(
    private val client: OkHttpClient,
    /**
     * Whether a `.part` left by a previous attempt may be continued with a Range request.
     *
     * True for a stable URL — a podcast enclosure names the same bytes every time, and those are
     * routinely 60–150MB fetched over the connection most likely to drop, so starting over on every
     * blip means a long episode on a poor signal never finishes at all.
     *
     * False wherever the URL is RE-RESOLVED between attempts: the signed-in YouTube fallback asks
     * again each time and may be handed a different audio format, and appending one format's bytes
     * to another's produces a file that will never play and that no retry can detect as broken.
     */
    private val resumable: Boolean = true,
) : DownloadStrategy {

    // audioOnly is moot here: a podcast enclosure is the audio.
    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> = flow {
        val url = item.fetchUrl
        if (url == null) {
            emit(DownloadState.Failed("Nothing to download"))
            return@flow
        }
        val partial = target.partialDownload()
        if (!resumable) partial.delete()
        val alreadyHave = partial.length()
        val request = Request.Builder().url(url.value)
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .build()
        if (alreadyHave > 0) {
            Diag.log("download", "resuming \"${item.item.title}\" from ${alreadyHave}B already on disk")
        }
        try {
            client.newCall(request).execute().use { response ->
                collectBody(response, item, target, partial, alreadyHave)
            }
        } catch (e: IOException) {
            // The partial is KEPT on purpose: it is the whole point of the `.part` file, and
            // deleting it is why every retry used to start at zero. `target` cannot hold anything
            // half-finished, so anything there is stale and goes.
            target.delete()
            Diag.warn("download", "\"${item.item.title}\" stopped with ${partial.length()}B kept for next time")
            emit(DownloadState.Failed(e.message ?: "network error"))
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads the response into [partial] and moves it into place, or says why not.
     *
     * Split from the call site because the alternative is one function with six exits inside a
     * `use` inside a `try` — and the exits are the interesting part.
     */
    private suspend fun FlowCollector<DownloadState>.collectBody(
        response: Response,
        item: PlayableItem,
        target: File,
        partial: File,
        alreadyHave: Long,
    ) {
        val refusal = response.refusal(item, target, partial, alreadyHave)
        if (refusal != null) {
            emit(DownloadState.Failed(refusal))
            return
        }
        // Appending ONLY on a 206 from the offset asked for. A server that ignores Range answers
        // 200 with the whole body, and appending that to what we hold is silent corruption.
        val continuing = response.code == PARTIAL_CONTENT && alreadyHave > 0
        if (alreadyHave > 0 && !continuing) {
            Diag.log(
                "download",
                "\"${item.item.title}\": the server ignored the range, so ${alreadyHave}B is discarded"
            )
        }
        val from = if (continuing) alreadyHave else 0L
        val body = response.body
        writeBody(
            input = body.byteStream(),
            partial = partial,
            from = from,
            total = body.contentLength().takeIf { it > 0 }?.plus(from),
            appending = continuing,
        )
        if (!partial.renameTo(target)) {
            // Rename is atomic on the one filesystem this ever touches (app-private storage), so a
            // refusal means something is at the target already. Copy rather than fail: the bytes are
            // right here, and losing them to a stale file would be absurd.
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        emit(DownloadState.Downloaded(target.absolutePath))
    }

    /**
     * Why this response cannot become a download, or null to go ahead. Tidies up as it decides,
     * because what is left on disk differs per refusal and only this knows which one happened.
     */
    private fun Response.refusal(item: PlayableItem, target: File, partial: File, alreadyHave: Long): String? {
        if (code == RANGE_NOT_SATISFIABLE) {
            // The part is longer than the resource: the feed replaced the file, or the part is junk.
            // Dropping it makes the next attempt a clean one; keeping it wedges the item forever.
            partial.delete()
            Diag.warn("download", "\"${item.item.title}\": no resume from ${alreadyHave}B; starting over")
            return "the server would not resume from $alreadyHave bytes, so the next attempt starts over"
        }
        if (!isSuccessful) return "HTTP $code"
        val type = header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && type in CANNOT_BE_MEDIA) {
            // Both files go: the caller creates the target before handing it over, so leaving it
            // would leave a zero-length file where an episode is meant to be — and an error page
            // appended to a real part file would poison the resume for good.
            target.delete()
            partial.delete()
            return "the server sent $type, which is not media"
        }
        return null
    }

    /**
     * Copies the body onto the end of [partial], reporting progress as the WHOLE download's
     * progress rather than this attempt's — a resumed 90%-complete episode reporting 0% is a lie,
     * and worse, it is indistinguishable from a download that keeps restarting.
     *
     * The stream is not closed here: the caller holds the response in a `use`, which closes it.
     */
    private suspend fun FlowCollector<DownloadState>.writeBody(
        input: InputStream,
        partial: File,
        from: Long,
        total: Long?,
        appending: Boolean,
    ) {
        var downloaded = from
        var lastEmitted = from
        FileOutputStream(partial, appending).use { out ->
            val buffer = ByteArray(BUFFER_BYTES)
            var read = input.read(buffer)
            while (read != -1) {
                out.write(buffer, 0, read)
                downloaded += read
                lastEmitted = reportProgress(downloaded, lastEmitted, total)
                read = input.read(buffer)
            }
        }
    }

    /** Emits progress at most every [EMIT_EVERY_BYTES], returning the mark to measure from next. */
    private suspend fun FlowCollector<DownloadState>.reportProgress(
        downloaded: Long,
        lastEmitted: Long,
        total: Long?,
    ): Long {
        if (downloaded - lastEmitted < EMIT_EVERY_BYTES) return lastEmitted
        emit(DownloadState.Downloading(downloaded, total))
        return downloaded
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val EMIT_EVERY_BYTES = 256 * 1024L
        const val PARTIAL_CONTENT = 206
        const val RANGE_NOT_SATISFIABLE = 416

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
