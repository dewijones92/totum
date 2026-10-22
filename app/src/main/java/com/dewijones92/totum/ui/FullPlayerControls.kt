package com.dewijones92.totum.ui

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.ui.player.PlaybackToggles
import com.dewijones92.totum.ui.player.QualityControl
import com.dewijones92.totum.ui.player.QueueControls
import com.dewijones92.totum.ui.player.WatchViewModel
import com.dewijones92.totum.video.VideoPlaybackLauncher

/**
 * The player's up-next list shows what follows the cursor, so its indices are offset from
 * the queue's own — done here once rather than inline at the call site.
 */
internal fun upNextControls(queue: PlaybackQueue, upNext: List<QueueEntry>): QueueControls {
    val indexNow = { entry: QueueEntry ->
        queue.state.value.entries.indexOfFirst { it.item.item.id == entry.item.item.id }.takeIf { it >= 0 }
    }
    return QueueControls(
        upNext = upNext,
        onPlay = { i -> upNext.getOrNull(i)?.let(indexNow)?.let(queue::jumpTo) },
        onRemove = { i -> upNext.getOrNull(i)?.let { queue.remove(it) } },
    )
}

internal fun qualityControl(
    quality: VideoPlaybackLauncher.QualityState,
    watchViewModel: WatchViewModel,
    watchTheAudioCopy: (() -> Unit)? = null,
) = QualityControl(
    options = quality.options,
    selectedId = quality.selectedId,
    onSelect = watchViewModel::selectQuality,
    canListen = quality.canListen || watchTheAudioCopy != null,
    listening = quality.listening || watchTheAudioCopy != null,
    onListen = watchViewModel::listen,
    onWatch = watchTheAudioCopy ?: watchViewModel::watch,
    audioTracks = quality.audioTracks,
    audioLanguage = quality.audioLanguage,
    onSelectAudioTrack = watchViewModel::selectAudioTrack,
)

internal fun playbackToggles(
    state: PlaybackState,
    controller: PlaybackController,
    container: AppContainer,
    settings: AppPreferences.Settings,
) = PlaybackToggles(
    skipSilence = state.skipSilence,
    onSetSkipSilence = controller::setSkipSilence,
    autoPlayNext = settings.autoPlayNext,
    onSetAutoPlayNext = container.appPreferences::setAutoPlayNext,
    sabrPlayback = settings.sabrPlayback,
    onSetSabrPlayback = container.appPreferences::setSabrPlayback,
    onSetVolumeBoost = controller::setVolumeBoost,
    repeatMode = settings.repeatMode,
    onSetRepeatMode = container.appPreferences::setRepeatMode,
    onKidLock = { container.kidLock.lock("the player's Kid lock tile") },
)
