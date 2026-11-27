package com.dewijones92.totum.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.SkipWhy
import com.dewijones92.totum.queue.upNextSkips
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.ui.common.LocalPlayStates
import com.dewijones92.totum.ui.common.LocalSnackbarOffers
import com.dewijones92.totum.ui.player.PlaybackToggles
import com.dewijones92.totum.ui.player.QualityControl
import com.dewijones92.totum.ui.player.QueueControls
import com.dewijones92.totum.ui.player.WatchViewModel
import com.dewijones92.totum.ui.queue.rememberQueueRemoveWithUndo
import com.dewijones92.totum.video.VideoPlaybackLauncher
import kotlinx.coroutines.launch

/**
 * The player's up-next list shows what follows the cursor, so its indices are offset from
 * the queue's own — done here once rather than inline at the call site.
 */
@Composable
internal fun upNextControls(queue: PlaybackQueue, upNext: List<QueueEntry>): QueueControls {
    val removeWithUndo = rememberQueueRemoveWithUndo(queue, LocalSnackbarOffers.current)
    val scope = rememberCoroutineScope()
    val playing by queue.nowPlaying.collectAsStateWithLifecycle()
    val skips = remember(upNext, playing, LocalPlayStates.current.value) {
        upNextSkips(upNext, playing?.item, queue.passOver).also { skipped ->
            Diag.log(
                "advance",
                "up next: ${skipped.size} of ${upNext.size} will be skipped on moving on " +
                    "[finished=${skipped.values.count { it is SkipWhy.PassedOver }} " +
                    "otherKind=${skipped.values.count { it is SkipWhy.OtherKind }} " +
                    "playing=${playing?.item?.id?.value}]",
            )
        }
    }
    val indexNow = { entry: QueueEntry ->
        queue.state.value.entries.indexOfFirst { it.item.item.id == entry.item.item.id }.takeIf { it >= 0 }
    }
    return QueueControls(
        upNext = upNext,
        onPlay = { i -> upNext.getOrNull(i)?.let(indexNow)?.let(queue::jumpTo) },
        onRemove = { i -> upNext.getOrNull(i)?.let(removeWithUndo) },
        onPlayInstead = { i ->
            upNext.getOrNull(i)?.let { entry -> scope.launch { queue.playInsteadOfCurrent(entry) } }
        },
        skips = skips,
        onMoveToEdge = { i, top ->
            upNext.getOrNull(i)?.let(indexNow)?.let { from ->
                queue.move(from, if (top) 0 else queue.state.value.entries.lastIndex)
            }
        },
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
