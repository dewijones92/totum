package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadStrategy
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
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
    private val resolveVideo: suspend (PlayableItem, Int?) -> VideoQuality? = { _, _ -> null },
    private val fetch: RangedFetch? = null,
    private val merge: suspend (File, File, File) -> String? = { _, _, _ -> "no merger" },
    private val sabr: SabrRoute = SabrRoute.NONE,
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> =
        download(item, target, audioOnly, maxHeight = null)

    override fun download(
        item: PlayableItem,
        target: File,
        audioOnly: Boolean,
        maxHeight: Int?,
    ): Flow<DownloadState> = flow {
        if (item.handle !is PlayHandle.Video) {
            emit(DownloadState.Failed("only a video can be fetched through the player"))
            return@flow
        }
        if (!audioOnly && bothStreams(item, target, maxHeight) { emit(it) }) return@flow
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
        val sabrBytes = sabr.bytes(url, item, target)
        val ranged = fetch
        if (sabrBytes == null && ranged != null) {
            emitAll(rangedSound(ranged, url, item, target))
            return@flow
        }
        if (sabrBytes == null) {
            Diag.log("download", "fetching \"${item.item.title}\" from its resolved audio URL")
            http.download(item.item.copy(mediaUrl = url).let { PlayableItem(it, PlayHandle.Podcast()) }, target, true)
                .collect { emit(if (it is DownloadState.Downloaded) it.copy(audioOnly = true) else it) }
            return@flow
        }
        emitAll(sabrBytes)
    }.flowOn(Dispatchers.IO)

    private suspend fun bothStreams(
        item: PlayableItem,
        target: File,
        maxHeight: Int?,
        emit: suspend (DownloadState) -> Unit,
    ): Boolean {
        val fetch = fetch ?: return false
        val quality = resolveVideo(item, maxHeight) ?: run {
            Diag.log(
                "download",
                "\"${item.item.title}\": the signed-in path gave no video stream; fetching the sound only",
            )
            return false
        }
        val work = File(
            target.parentFile,
            "${target.nameWithoutExtension}.parts"
        ).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val audioUrl = quality.audioUrl
            val video = File(work, "video")
            val audio = File(work, "audio")
            val total = listOfNotNull(
                RangedFetch.lengthOf(quality.videoUrl),
                audioUrl?.let(RangedFetch::lengthOf)
            ).sum()
            Diag.log(
                "download",
                "\"${item.item.title}\" as ${quality.label} video + audio through the signed-in path (${total}B, " +
                    "${if (audioUrl == null) "one muxed stream" else "two streams to merge"})",
            )
            val expected = total.takeIf { it > 0 }
            emit(DownloadState.Downloading(0, expected))
            val why = fetch.fetch(quality.videoUrl, video) { emit(DownloadState.Downloading(it, expected)) }
                ?: audioUrl?.let { url ->
                    val videoBytes = video.length()
                    fetch.fetch(url, audio) { emit(DownloadState.Downloading(videoBytes + it, expected)) }
                }
                ?: if (audioUrl == null) null else merge(video, audio, File(work, "merged"))
            if (why != null) {
                Diag.warn("download", "\"${item.item.title}\" video + audio failed ($why); fetching the sound only")
                return false
            }
            val result = if (audioUrl == null) video else File(work, "merged")
            target.delete()
            if (!result.renameTo(target)) result.copyTo(target, overwrite = true)
            Diag.log(
                "download",
                "\"${item.item.title}\" downloaded as ${quality.label} video + audio (${target.length()}B)"
            )
            emit(DownloadState.Downloaded(target.absolutePath, audioOnly = false))
            return true
        } finally {
            work.deleteRecursively()
        }
    }

    private fun rangedSound(ranged: RangedFetch, url: HttpUrl, item: PlayableItem, target: File): Flow<DownloadState> =
        flow {
            val expected = RangedFetch.lengthOf(url)
            Diag.log(
                "download",
                "fetching \"${item.item.title}\" from its resolved audio URL in ranges (${expected ?: -1}B expected)",
            )
            val started = System.nanoTime()
            emit(DownloadState.Downloading(0, expected))
            val why = ranged.fetch(url, target) { emit(DownloadState.Downloading(it, expected)) }
            val seconds = (System.nanoTime() - started) / NANOS_PER_SECOND.toDouble()
            if (why != null) {
                target.delete()
                Diag.warn(
                    "download",
                    "\"${item.item.title}\": the ranged fetch failed after ${"%.1f".format(seconds)}s ($why)"
                )
                emit(DownloadState.Failed("the signed-in audio fetch failed: $why"))
                return@flow
            }
            val bytes = target.length()
            Diag.log(
                "download",
                "\"${item.item.title}\" sound fetched in ranges: ${bytes}B in ${"%.1f".format(seconds)}s " +
                    "(${(bytes / BYTES_PER_KB / seconds.coerceAtLeast(MIN_SECONDS)).toLong()} KB/s)",
            )
            emit(DownloadState.Downloaded(target.absolutePath, audioOnly = true))
        }

    // A function rather than the call inline: Android lint does not follow an opt-in into a
    // lambda, and `download` builds its work inside `flow { }`. Same workaround as PlaybackService.
    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val BYTES_PER_KB = 1024.0
        const val MIN_SECONDS = 0.001
    }
}
