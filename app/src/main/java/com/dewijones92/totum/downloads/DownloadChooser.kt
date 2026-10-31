package com.dewijones92.totum.downloads

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.domain.offersDownloadChoice
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.downloads.home.HomeDownloads
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.settings.DownloadPlace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class DownloadChooser(
    private val prefs: AppPreferences,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
    private val home: HomeDownloads? = null,
    private val metered: () -> Boolean = { false },
) {
    data class Ask(
        val item: MediaItem,
        val pillar: MediaKind,
        val videoOnly: Boolean,
        val askWhat: Boolean = true,
        val askWhere: Boolean = false,
        val suggestedPlace: DownloadPlace = DownloadPlace.PHONE,
    )

    val homeAvailable: Boolean get() = home?.available == true

    fun suggestedPlace(): DownloadPlace = prefs.settings.value.downloadPlace
        ?: if (homeAvailable && metered()) DownloadPlace.HOME else DownloadPlace.PHONE

    private val asking = MutableSharedFlow<Ask>(extraBufferCapacity = ASKS_HELD)
    val asks: SharedFlow<Ask> = asking.asSharedFlow()

    fun request(
        item: MediaItem,
        pillar: MediaKind = item.pillar,
        askAnyway: Boolean = false,
        videoOnly: Boolean = false,
    ) {
        val askWhere = homeAvailable && (askAnyway || prefs.settings.value.downloadPlace == null)
        val place = suggestedPlace()
        if (!item.offersDownloadChoice(pillar)) {
            val song = item.contentKind == MediaContentKind.MUSIC
            val fixed = DownloadChoice.Sound.takeIf { song }
            if (askWhere) {
                Diag.log(TAG, "\"${item.title}\": no sound-or-video choice; asking only where (suggested $place)")
                asking.tryEmit(Ask(item, pillar, videoOnly, askWhat = false, askWhere = true, suggestedPlace = place))
                return
            }
            Diag.log(
                TAG,
                "\"${item.title}\" has no sound-or-video choice; downloading " +
                    (if (song) "its sound, as every song is" else "the whole file") + " ${place.where()}",
            )
            start(item, fixed, place)
            return
        }
        val remembered = prefs.settings.value.downloadChoice?.takeIf { !videoOnly || it is DownloadChoice.Video }
        if (remembered != null && !askAnyway && !askWhere) {
            Diag.log(TAG, "\"${item.title}\": the remembered choice ${remembered.stored} ${place.where()}; not asking")
            start(item, remembered, place)
            return
        }
        Diag.log(
            TAG,
            "\"${item.title}\": asking (askAnyway=$askAnyway videoOnly=$videoOnly askWhere=$askWhere " +
                "remembered=${prefs.settings.value.downloadChoice?.stored ?: "none"} suggested=$place)",
        )
        asking.tryEmit(Ask(item, pillar, videoOnly, askWhat = true, askWhere = askWhere, suggestedPlace = place))
    }

    fun requestAllRemembered(items: List<MediaItem>): Boolean {
        val remembered = prefs.settings.value.downloadChoice ?: return false
        if (homeAvailable && prefs.settings.value.downloadPlace == null) return false
        Diag.log(TAG, "${items.size} selected: the remembered choice ${remembered.stored}; not asking")
        items.forEach { request(it) }
        return true
    }

    fun choose(ask: Ask, choice: DownloadChoice?, place: DownloadPlace, remember: Boolean) {
        if (remember) {
            if (ask.askWhat && choice != null) prefs.setDownloadChoice(choice)
            if (ask.askWhere) prefs.setDownloadPlace(place)
        }
        Diag.log(
            TAG,
            "\"${ask.item.title}\": chose ${choice?.stored ?: "the whole file"} ${place.where()}" +
                if (remember) ", remembered" else "",
        )
        val song = ask.item.contentKind == MediaContentKind.MUSIC
        start(ask.item, choice ?: DownloadChoice.Sound.takeIf { song }, place)
    }

    private fun start(item: MediaItem, choice: DownloadChoice?, place: DownloadPlace) {
        val atHome = home?.takeIf { place == DownloadPlace.HOME && it.available }
        if (atHome != null) {
            atHome.start(item.asPlayable(), choice)
            return
        }
        scope.launch {
            when (choice) {
                DownloadChoice.Sound -> downloads.download(item, audioOnly = true)
                is DownloadChoice.Video -> downloads.download(item, audioOnly = false, maxHeight = choice.maxHeight)
                null -> downloads.download(item, audioOnly = false)
            }
        }
    }

    private fun DownloadPlace.where(): String = if (this == DownloadPlace.HOME) "at home" else "on the phone"

    private companion object {
        const val TAG = "download"
        const val ASKS_HELD = 8
    }
}
