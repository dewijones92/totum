package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.SubtitleFormat
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.domain.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

public data class SubtitleFile(
    val file: TorrentFile,
    val languageCode: String,
    val label: String,
    val format: SubtitleFormat,
)

public object TorrentSubtitles {

    public fun forVideo(torrent: PreparedTorrent, video: TorrentFile): List<SubtitleFile> {
        val folder = video.path.substringBeforeLast('/', "")
        val stem = video.name.substringBeforeLast('.')
        val subtitles = torrent.files.filter { it.format() != null }
        val onlyVideo = torrent.files.count { it.isPlayable } == 1
        val matched = subtitles.filter { sub ->
            val beside = sub.path.substringBeforeLast('/', "") == folder && sub.name.startsWith("$stem.")
            val inItsSubsFolder = sub.path.startsWith(prefixed(folder, "Subs/$stem/")) ||
                sub.path.startsWith(prefixed(folder, "Subs/$stem."))
            val inTheOnlySubsFolder = onlyVideo && sub.path.startsWith(prefixed(folder, "Subs/"))
            beside || inItsSubsFolder || inTheOnlySubsFolder
        }
        val byLanguage = matched.groupBy { languageOf(it.name.substringBeforeLast('.')) }
        return byLanguage.flatMap { (language, files) ->
            files.sortedBy { it.path }.mapIndexed { i, file ->
                val name = LANGUAGE_NAMES[language] ?: language
                val label = if (files.size == 1) "$name (in the torrent)" else "$name ${i + 1} (in the torrent)"
                SubtitleFile(file, language, label, file.format()!!)
            }
        }
    }

    internal fun languageOf(stem: String): String {
        val words = stem.lowercase().split('.', '_', '-', ' ', '[', ']', '(', ')').filter { it.isNotEmpty() }
        return words.asReversed().firstNotNullOfOrNull { LANGUAGE_WORDS[it] } ?: UNDETERMINED
    }

    private fun prefixed(folder: String, rest: String) = if (folder.isEmpty()) rest else "$folder/$rest"

    private fun TorrentFile.format(): SubtitleFormat? = SubtitleFormat.fromExtension(name.substringAfterLast('.', ""))

    private const val UNDETERMINED = "und"
    private val LANGUAGE_WORDS = mapOf(
        "english" to "en", "eng" to "en", "en" to "en",
        "welsh" to "cy", "cym" to "cy", "cy" to "cy",
        "french" to "fr", "fre" to "fr", "fra" to "fr", "fr" to "fr",
        "spanish" to "es", "spa" to "es", "es" to "es",
        "german" to "de", "ger" to "de", "deu" to "de", "de" to "de",
        "italian" to "it", "ita" to "it", "it" to "it",
        "dutch" to "nl", "nld" to "nl", "nl" to "nl",
        "portuguese" to "pt", "por" to "pt", "pt" to "pt",
    )
    private val LANGUAGE_NAMES = mapOf(
        "en" to "English", "cy" to "Welsh", "fr" to "French", "es" to "Spanish", "de" to "German",
        "it" to "Italian", "nl" to "Dutch", "pt" to "Portuguese", UNDETERMINED to "Subtitles",
    )
}

public class TorrentSidecarSubtitles(
    private val server: HomeTorrentServer,
    private val waitMs: Long = WAIT_MS,
    private val lateWaitMs: Long = LATE_WAIT_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val prepared = ConcurrentHashMap<String, PreparedTorrent>()
    private val inFlight = ConcurrentHashMap<String, Deferred<PreparedTorrent?>>()
    private val timedOutBefore = ConcurrentHashMap.newKeySet<String>()

    public fun remember(torrent: PreparedTorrent) {
        prepared[torrent.hash] = torrent
    }

    private suspend fun fileListOf(hash: String): PreparedTorrent? {
        prepared[hash]?.let { return it }
        val job = inFlight.computeIfAbsent(hash) {
            scope.async { server.prepare(TorrentLink.Magnet("magnet:?xt=urn:btih:$hash"))?.also(::remember) }
                .also { started -> started.invokeOnCompletion { inFlight.remove(hash) } }
        }
        val patience = if (hash in timedOutBefore) lateWaitMs else waitMs
        val got = withTimeoutOrNull(patience) { job.await() }
        if (got == null && job.isActive) {
            val first = timedOutBefore.add(hash)
            Diag.log(
                TAG,
                "$hash: no file list within ${patience}ms; it keeps being fetched" +
                    if (first) ", and the next lookup waits up to ${lateWaitMs}ms" else "",
            )
        }
        return got
    }

    public suspend fun forItem(item: MediaItem): List<SubtitleTrack> {
        val parts = item.id.value.split(':')
        if (parts.size != ID_PARTS || parts[0] != "torrent") return emptyList()
        val (hash, index) = parts[1] to parts[2].toIntOrNull()
        val torrent = fileListOf(hash)
        val video = torrent?.files?.firstOrNull { it.index == index }
        if (torrent == null || video == null) {
            Diag.log(TAG, "${item.id.value}: no file list yet, so no subtitle files looked for")
            return emptyList()
        }
        val found = TorrentSubtitles.forVideo(torrent, video)
        Diag.log(
            TAG,
            "${item.id.value}: ${found.size} subtitle file(s) in the torrent for \"${video.name}\"" +
                found.joinToString(prefix = if (found.isEmpty()) "" else ": ") { "${it.languageCode}=${it.file.path}" },
        )
        return found.map { sub ->
            SubtitleTrack(
                sub.languageCode,
                sub.label,
                server.stream(torrent, sub.file),
                autoGenerated = false,
                sub.format
            )
        }
    }

    private companion object {
        const val TAG = "subtitles"
        const val WAIT_MS = 1_500L
        const val LATE_WAIT_MS = 8_000L
        const val ID_PARTS = 3
    }
}
