package com.dewijones92.totum.video

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadStrategy
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.playback.sabrStreamFor
import com.dewijones92.totum.sabr.SabrStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Downloads a video the way the app PLAYS it — through its own signed-in resolution.
 *
 * The path exists because of an asymmetry Dewi hit on 2026-08-06: YouTube serves members-only
 * videos to the signed-in app and refuses them to yt-dlp, which has no account (it removed OAuth
 * login, and the app holds TV device-code tokens for InnerTube, which is a different mechanism).
 * So three Novara episodes in his queue could be played and never downloaded.
 *
 * It resolves through the SAME [VideoResolver] playback uses — so whatever reaches a video for
 * playing reaches it for downloading, with no second idea of how to get at a stream — and then
 * copies the audio. Two kinds of URL come back and both are handled:
 *
 *  - a **`sabr://`** URL, which is how an authenticated stream is fetched at all: a plain ranged GET
 *    of an ANDROID-client URL serves its first megabyte and then 403s forever (measured 2026-07-31).
 *  - an ordinary **https** URL, for everything else.
 *
 * Audio only, deliberately. It is what the queue's automatic fetch wants, it needs no muxing, and
 * the full-video case already works through yt-dlp for every video that is not gated.
 */
class PlayerBackedDownloadStrategy(
    /** The audio URL for a watch URL, resolved exactly as playback resolves it. */
    private val resolveAudioUrl: suspend (PlayableItem) -> HttpUrl?,
    /** Plain-HTTP fetch, so an ordinary URL is not downloaded twice in two ways. */
    private val http: DownloadStrategy,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> = flow {
        if (item.handle !is PlayHandle.Video) {
            emit(DownloadState.Failed("only a video can be fetched through the player"))
            return@flow
        }
        val url = resolveAudioUrl(item)
        if (url == null) {
            // Named, because "the signed-in path also failed" and "the signed-in path never ran"
            // are different problems and this is the only place that can tell them apart.
            emit(DownloadState.Failed("the signed-in path could not resolve an audio stream either"))
            return@flow
        }
        // ASKED, never sniffed. A SABR URL is not a scheme — it is the real endpoint with this
        // app's markers appended (`__totum_video=`, `__totum_itag=`), so a `startsWith("sabr://")`
        // test says no to every real one. That mistake sent gated videos down the plain-HTTP path,
        // which fetched the SABR endpoint with a GET and wrote the 2KB refusal as a "download":
        // a file that existed, played nothing, and reported success. Caught by the instrumented
        // test on 2026-08-06. One call decides, the same one playback uses.
        val stream = streamFor(url)
        if (stream == null) {
            Diag.log("download", "fetching \"${item.item.title}\" from its resolved audio URL")
            http.download(item.item.copy(mediaUrl = url).let { PlayableItem(it, PlayHandle.Podcast()) }, target, true)
                .collect { emit(if (it is DownloadState.Downloaded) it.copy(audioOnly = true) else it) }
            return@flow
        }
        emitAll(sabrBytes(stream, item, target))
    }.flowOn(Dispatchers.IO)

    // A function rather than the call inline: Android lint does not follow an opt-in into a
    // lambda, and `download` builds its work inside `flow { }`. Same workaround as PlaybackService.
    @OptIn(UnstableApi::class)
    private fun streamFor(url: HttpUrl): SabrStream? = sabrStreamFor(url.value)

    /**
     * Copies a SABR stream to [target].
     *
     * Progress is reported by bytes written against the format's own length when it has one, so a
     * long fetch is not a spinner. The stream ends by returning nothing, exactly as it does for the
     * player.
     */
    @OptIn(UnstableApi::class)
    private fun sabrBytes(stream: SabrStream, item: PlayableItem, target: File): Flow<DownloadState> = flow {
        val total = stream.contentLength
        Diag.log(
            "download",
            "fetching \"${item.item.title}\" over SABR (${total ?: -1} bytes expected)",
        )
        emit(DownloadState.Downloading(0, total))
        var written = 0L
        target.parentFile?.mkdirs()
        target.outputStream().use { out ->
            while (true) {
                val chunk = stream.read(written)
                if (chunk.isEmpty()) break
                out.write(chunk)
                written += chunk.size
                emit(DownloadState.Downloading(written, total))
            }
        }
        // A file that exists is not a file that plays. An empty fetch, or one that stopped far
        // short of the length the format itself declared, is a FAILURE — recorded as one, so the
        // item is retried rather than sitting in Library as a download that plays nothing.
        val short = total != null && written < total * ENOUGH_PERCENT / PERCENT
        if (written == 0L || short) {
            target.delete()
            emit(
                DownloadState.Failed(
                    "the signed-in path returned ${written}B of ${total ?: -1}B",
                ),
            )
            return@flow
        }
        Diag.log("download", "SABR fetch of \"${item.item.title}\" finished at $written bytes")
        emit(DownloadState.Downloaded(target.absolutePath, audioOnly = true))
    }

    private companion object {
        /**
         * How much of a declared length counts as the whole thing.
         *
         * Not 100%: SABR serves what it decides to and a final run can end a hair short, so an
         * exact test would fail good downloads. Low enough that a truncated one cannot pass.
         */
        const val ENOUGH_PERCENT = 95
        const val PERCENT = 100
    }
}
