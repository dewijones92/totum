package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class EpisodeDownloads(private val downloads: DownloadManager, private val scope: CoroutineScope) {

    data class Ask(val title: String, val items: List<PlayableItem>) {
        val offersAChoice: Boolean get() = items.any { it.handle is PlayHandle.Video }
    }

    private val _asking = MutableStateFlow<Ask?>(null)
    val asking: StateFlow<Ask?> = _asking.asStateFlow()

    fun ask(title: String, items: List<PlayableItem>) {
        Diag.log(TAG, "choose episodes of \"$title\": ${items.size} offered, all ticked")
        _asking.value = Ask(title, items)
    }

    fun dismiss() {
        _asking.value?.let { Diag.log(TAG, "choose episodes of \"${it.title}\": closed") }
        _asking.value = null
    }

    fun start(ask: Ask, chosen: Set<MediaItemId>, choice: DownloadChoice) {
        val picked = ask.items.filter { it.item.id in chosen }
        Diag.log(
            TAG,
            "downloading ${picked.size} of ${ask.items.size} from \"${ask.title}\" as ${choice.stored} " +
                "(a few at a time; torrents and podcasts as their files)",
        )
        _asking.value = null
        scope.launch {
            picked.forEach { item ->
                when (item.handle) {
                    is PlayHandle.Video -> when (choice) {
                        DownloadChoice.Sound -> downloads.download(item, audioOnly = true)
                        is DownloadChoice.Video -> downloads.download(
                            item,
                            audioOnly = false,
                            maxHeight = choice.maxHeight
                        )
                    }
                    is PlayHandle.Podcast, is PlayHandle.LocalVideo -> downloads.download(item, audioOnly = false)
                }
            }
        }
    }

    private companion object {
        const val TAG = "download"
    }
}
