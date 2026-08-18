package com.dewijones92.totum.data.download

import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.DownloadRequest
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Downloads a video through the yt-dlp engine, which fetches the separate
 * best-quality video and audio streams and merges them with the bundled ffmpeg
 * (a plain HTTP GET of one stream can't do that). Optionally cuts SponsorBlock
 * segments out of the finished file so downloads match playback.
 *
 * yt-dlp picks its own filename and container, so the download lands in a temp
 * directory and the single result is moved onto the manager's [target].
 */
public class EngineDownloadStrategy(
    private val engine: YtDlpEngine,
    private val sponsorBlockCategories: Set<String> = emptySet(),
    /**
     * Audio languages to prefer, best first — the same preference playback uses.
     *
     * It was missing entirely, so choosing German and DOWNLOADING gave you the English original, with
     * no track menu offline to correct it with. This is the PRIMARY video strategy, so the language work
     * done on the resolve path did not reach the case where it matters most: a downloaded file is the
     * one you cannot re-pick a track for.
     */
    private val preferredAudioLanguages: () -> List<String> = { emptyList() },
) : DownloadStrategy {

    override fun download(item: PlayableItem, target: File, audioOnly: Boolean): Flow<DownloadState> = flow {
        val url = item.fetchUrl
        if (url == null) {
            emit(DownloadState.Failed("Nothing to download"))
            return@flow
        }
        val work = File(target.parentFile, "${target.nameWithoutExtension}.part").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val request = DownloadRequest(
                url = url,
                targetDirectory = work,
                formatId = selectorFor(audioOnly),
                sponsorBlockCategories = sponsorBlockCategories,
            )
            engine.download(request).collect { event ->
                when (event) {
                    is DownloadEvent.Started -> emit(DownloadState.Downloading(0, null))
                    is DownloadEvent.Progress -> emit(progress(event))
                    is DownloadEvent.Completed -> {
                        target.delete()
                        // Same filesystem (temp dir is under target's parent), so a
                        // rename is instant; copy only if the platform refuses it.
                        if (!event.file.renameTo(target)) event.file.copyTo(target, overwrite = true)
                        // Stamp the variant so a later request for the full video isn't
                        // mistaken for satisfied by an audio-only file.
                        emit(DownloadState.Downloaded(target.absolutePath, audioOnly = audioOnly))
                    }
                    is DownloadEvent.Failed -> emit(DownloadState.Failed(event.reason.toString()))
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }.flowOn(Dispatchers.IO)

    private fun progress(event: DownloadEvent.Progress): DownloadState.Downloading {
        // yt-dlp's total can lag behind bytes for estimates; drop it rather than
        // trip DownloadState's total >= downloaded invariant.
        val total = event.totalBytes?.takeIf { it >= event.bytesDownloaded }
        return DownloadState.Downloading(event.bytesDownloaded, total)
    }

    /**
     * The yt-dlp selector, preferring the wanted audio language and falling back to the plain one.
     *
     * `[language^=de]` matches `de`, `de-DE` and the dubbed variants alike, and the `/` alternative means
     * a video with no such track still downloads rather than failing -- the fallback IS the point,
     * because most videos have exactly one audio track and must be unaffected.
     */
    private fun selectorFor(audioOnly: Boolean): String {
        val base = if (audioOnly) BEST_AUDIO else BEST_MERGED
        val wanted = preferredAudioLanguages().firstOrNull()?.takeIf { it.isNotBlank() } ?: return base
        val language = wanted.substringBefore('-')
        return if (audioOnly) "ba[language^=$language]/$base" else "bv*+ba[language^=$language]/$base"
    }

    public companion object {
        /** Best video + best audio, merged; falls back to the best single stream. */
        private const val BEST_MERGED = "bv*+ba/b"

        /** Best audio-only stream, no merge needed — small and quick. */
        private const val BEST_AUDIO = "ba/b"
    }
}
