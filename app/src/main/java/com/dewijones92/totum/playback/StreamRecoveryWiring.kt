package com.dewijones92.totum.playback

import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.NetworkStatus
import com.dewijones92.totum.video.VideoResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

@Suppress("LongParameterList")
internal fun PlaybackQueue.startStreamRecovery(
    controller: PlaybackController,
    resolver: VideoResolver,
    network: NetworkStatus,
    autoPlayNext: () -> Boolean,
    prefetchOne: suspend (PlayableItem) -> Unit,
    scope: CoroutineScope,
) {
    StreamRecovery(
        failures = controller.streamFailures,
        replay = this::replayCurrent,
        moveOn = { playNextInQueue() },
        autoPlayNext = autoPlayNext,
        // The last thing to try before abandoning the item: the copy already on the disk.
        playWithoutTheStream = this::playCurrentWithoutItsStream,
        // The sound without the picture, tried after the disk and before giving up. There is no
        // durable VIDEO stream to be had for a long video — see StreamRecovery's parameter doc —
        // so this is the difference between losing the picture and losing the item.
        playWithoutThePicture = { at -> playCurrentWithoutThePicture(at) },
        // The rung that keeps the picture. Deliberately NOT gated on the sabrPlayback setting:
        // that setting decides whether SABR is the primary route (it should not be — 1080p30, no
        // seeking), while this is the last chance to show a picture at all.
        playOverSabr = { at -> playCurrentOverSabr(at) },
        // Choosing something by hand is a new stuck point. Without this the budget carried
        // over from the give-up, and report 0.1.383's two hand-taps of a failed video were
        // each refused on their first error, with no retry and no re-resolve.
        freshStarts = freshStarts,
        isPlaying = { id ->
            controller.state.value?.let { it.itemId == id && it.isPlaying } == true
        },
        forgetResolved = this::forgetResolved,
        forgetHeldStreams = { forgetLiveSabrStreamsFor(it.value) },
        // A SABR stall must stop that item being resolved over SABR again. Without this, recovery
        // forgets the resolution, `extractAndCache` asks overSabr() first (the setting is on), and the
        // retry goes straight back to the route that just stalled -- burning the budget before the
        // ladder can fall through to extraction, which is the route that can seek.
        onSabrStalled = resolver::sabrStalled,
        // Started on the first failure, so the 20-25s extraction overlaps the retries
        // instead of following them. Report 0.1.277: 58s of silence, 28 of it after the app
        // had already given up on the dead stream.
        prefetchNext = { peekNext()?.let { prefetchOne(it) } },
        awaitNetwork = network::awaitOnline,
        scope = scope,
    ).start()
}

internal fun PlaybackQueue.handOverFinishedDownloads(downloads: DownloadManager, scope: CoroutineScope) {
    scope.launch {
        downloads.events()
            .filter { it.state is DownloadState.Downloaded }
            .collect { handOverToTheDownload(it.item.id) }
    }
}
