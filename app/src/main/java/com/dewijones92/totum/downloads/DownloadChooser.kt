package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.offersDownloadChoice
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class DownloadChooser(
    private val prefs: AppPreferences,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
) {
    data class Ask(val item: MediaItem, val pillar: MediaKind, val videoOnly: Boolean)

    private val asking = MutableSharedFlow<Ask>(extraBufferCapacity = ASKS_HELD)
    val asks: SharedFlow<Ask> = asking.asSharedFlow()

    fun request(
        item: MediaItem,
        pillar: MediaKind = item.pillar,
        askAnyway: Boolean = false,
        videoOnly: Boolean = false,
    ) {
        if (!item.offersDownloadChoice(pillar)) {
            Diag.log(TAG, "\"${item.title}\" is one file, with no sound or video to choose; downloading it whole")
            start(item, null)
            return
        }
        val remembered = prefs.settings.value.downloadChoice?.takeIf { !videoOnly || it is DownloadChoice.Video }
        if (remembered != null && !askAnyway) {
            Diag.log(TAG, "\"${item.title}\": the remembered choice ${remembered.stored}; not asking")
            start(item, remembered)
            return
        }
        Diag.log(
            TAG,
            "\"${item.title}\": asking (askAnyway=$askAnyway videoOnly=$videoOnly " +
                "remembered=${prefs.settings.value.downloadChoice?.stored ?: "none"})",
        )
        asking.tryEmit(Ask(item, pillar, videoOnly))
    }

    fun requestAllRemembered(items: List<MediaItem>): Boolean {
        val remembered = prefs.settings.value.downloadChoice ?: return false
        Diag.log(TAG, "${items.size} selected: the remembered choice ${remembered.stored}; not asking")
        items.forEach { request(it) }
        return true
    }

    fun choose(ask: Ask, choice: DownloadChoice, remember: Boolean) {
        if (remember) prefs.setDownloadChoice(choice)
        Diag.log(TAG, "\"${ask.item.title}\": chose ${choice.stored}${if (remember) ", remembered" else ""}")
        start(ask.item, choice)
    }

    private fun start(item: MediaItem, choice: DownloadChoice?) {
        scope.launch {
            when (choice) {
                DownloadChoice.Sound -> downloads.download(item, audioOnly = true)
                is DownloadChoice.Video -> downloads.download(item, audioOnly = false, maxHeight = choice.maxHeight)
                null -> downloads.download(item, audioOnly = false)
            }
        }
    }

    private companion object {
        const val TAG = "download"
        const val ASKS_HELD = 8
    }
}
