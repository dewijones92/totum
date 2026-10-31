package com.dewijones92.totum.downloads

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.torrent.TorrentProgress
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.playback.cast.EmbeddedSubtitles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

internal class EmbeddedSubtitleWarmer(
    private val context: Context,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
) {
    fun start(afterMs: Long) {
        scope.launch(Dispatchers.IO) {
            delay(afterMs)
            downloads.observeDownloads()
                .map { states ->
                    states.filter { (id, state) ->
                        id.value.startsWith(TorrentProgress.ITEM_PREFIX) && state is DownloadState.Downloaded
                    }.values.map { (it as DownloadState.Downloaded).localPath }.sorted()
                }
                .distinctUntilChanged()
                .collect { paths -> prepare(paths.map(::File)) }
        }
    }

    private fun prepare(files: List<File>) {
        val waiting = files.filter { it.exists() && !EmbeddedSubtitles.isDone(context, it) }
        if (waiting.isEmpty()) return
        Diag.log(
            "subtitles",
            "saving the own subtitles of ${waiting.size} downloaded torrent file(s) for casting, one at a time"
        )
        waiting.forEach { file ->
            runCatching { EmbeddedSubtitles.prepare(context, file) }
                .onFailure { Diag.warn("subtitles", "could not save ${file.name}'s own subtitles", it) }
        }
    }
}
