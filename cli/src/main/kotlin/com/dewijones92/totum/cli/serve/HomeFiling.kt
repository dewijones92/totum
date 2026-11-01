package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.cli.serve.filing.LibraryFiler
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.download.JsonDownloadStore
import com.dewijones92.totum.data.torrent.TorrentPlayables
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal class HomeFiling(
    private val store: JsonDownloadStore,
    private val downloadDir: File,
    private val filer: LibraryFiler?,
) {
    private val filing = Mutex()
    private val tried: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun start(scope: CoroutineScope, records: Flow<List<DownloadRecord>>) {
        scope.launch {
            relocateMoved(records.first())
            if (filer == null) {
                Diag.log("filing", "no library configured, so finished torrents and videos stay in ${downloadDir.path}")
            } else {
                records.collect { fileFinished(filer, it, scope) }
            }
        }
    }

    private suspend fun relocateMoved(records: List<DownloadRecord>) {
        records.forEach { record ->
            val path = (record.state as? DownloadState.Downloaded)?.localPath ?: return@forEach
            val here = File(downloadDir, File(path).name)
            if (!File(path).isFile && here.isFile) {
                store.relocate(record.item.item.id, here.path)
                Diag.log("filing", "record ${record.item.item.id.value} pointed at $path; it is at ${here.path}")
            }
        }
    }

    private fun fileFinished(filer: LibraryFiler, records: List<DownloadRecord>, scope: CoroutineScope) {
        val home = downloadDir.canonicalFile
        records.forEach { record ->
            val done = record.state as? DownloadState.Downloaded ?: return@forEach
            val item = record.item
            val torrent = item.item.sourceId == TorrentPlayables.SOURCE
            val youTube = item.handle is PlayHandle.Video
            val fresh = (torrent || youTube) && !done.audioOnly &&
                File(done.localPath).canonicalFile.parentFile == home
            if (!fresh || !tried.add(item.item.id.value)) return@forEach
            val file = File(done.localPath)
            val name = if (torrent) torrentFileName(item) else "${item.item.title}.${file.extension}"
            val from = if (torrent) item.item.author else "YouTube channel: ${item.item.author ?: "unknown"}"
            scope.launch {
                filing.withLock {
                    val outcome = filer.file(file, name, from)
                    if (outcome is LibraryFiler.Outcome.Moved) store.relocate(item.item.id, outcome.file.path)
                }
            }
        }
    }
}

internal fun torrentFileName(item: PlayableItem): String {
    val segment = item.item.mediaUrl?.value?.substringAfter("/stream/", "")?.substringBefore('?').orEmpty()
    val decoded = runCatching { URLDecoder.decode(segment.replace("+", "%2B"), Charsets.UTF_8) }.getOrDefault(segment)
    return decoded.substringAfterLast('/').ifBlank { item.item.title }
}
