package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.downloads.DownloadKeepAliveService
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.QueueAutoDownloader
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.OutboundSyncStatus

/**
 * What a report says about the app at the moment it is sent — playback, the queue, what is on the
 * disk, the settings, the account, the network. The [capture] runs on whatever thread just crashed,
 * so every block is defensive and every read is from a cached value, never a blocking call: a
 * diagnostic must never be the thing that hangs or throws.
 */
internal class DiagnosticSnapshot(
    private val playbackController: PlaybackController,
    private val playbackQueue: PlaybackQueue,
    private val accountSubscriptions: AccountSubscriptions,
    private val live: Live,
) {
    /** Values read at capture time — each from a cached value, never a blocking call. */
    class Live(
        val downloadStates: () -> Map<MediaItemId, DownloadState>,
        val settings: () -> AppPreferences.Settings,
        /** Whether listening is reaching the account, and how many updates are held waiting to. */
        val accountSync: () -> Pair<OutboundSyncStatus, Int>,
        val isMetered: () -> Boolean,
    )

    fun capture(): Map<String, String> = buildMap {
        runCatching {
            val state = playbackController.state.value
            put("playing.title", state?.title ?: "nothing")
            put("playing.itemId", state?.itemId?.value ?: "-")
            put("playing.kind", state?.kind?.name ?: "-")
            put("playing.positionMs", state?.positionMs?.toString() ?: "-")
            put("playing.hasVideo", state?.hasVideo?.toString() ?: "-")
            put("playing.speed", state?.speed?.toString() ?: "-")
            put("playing.skipSilence", state?.skipSilence?.toString() ?: "-")
            put("playing.volumeBoost", state?.volumeBoost?.name ?: "-")
        }
        runCatching {
            val queue = playbackQueue.state.value
            put("queue.size", queue.entries.size.toString())
            put("queue.currentIndex", queue.currentIndex.toString())
            put("queue.items", queue.entries.joinToString(" | ") { "${it.item.item.title}" })
        }
        runCatching { putDownloadState() }
        runCatching {
            val settings = live.settings()
            put("settings.playbackMode", settings.playbackMode.name)
            put("settings.autoPlayNext", settings.autoPlayNext.toString())
            put("settings.autoDownloadQueue", settings.autoDownloadQueue.toString())
            put("settings.autoDownloadWifiOnly", settings.autoDownloadWifiOnly.toString())
            put("settings.wifiMaxHeight", settings.wifiMaxHeight.toString())
            put("settings.cellularMaxHeight", settings.cellularMaxHeight.toString())
        }
        runCatching {
            // The account's subscription list, because "it offered me Subscribe to a channel I
            // follow" is unanswerable without knowing how many channels the app thinks it has.
            val subs = accountSubscriptions.channels.value
            put("account.signedIn", accountSubscriptions.signedIn.value.toString())
            // Whether listening is REACHING the account, and how much is waiting to. `NoSession` in the
            // trail was indistinguishable from working for three weeks; this line is the difference.
            val (outbound, pending) = live.accountSync()
            put("yt-sync.outbound", outbound.toString())
            put("yt-sync.pendingUpdates", pending.toString())
            put("account.subscriptions", subs.size.toString())
            put("account.subscriptionTitles", subs.joinToString(" | ") { it.title })
        }
        runCatching { put("network.metered", live.isMetered().toString()) }
    }

    private fun MutableMap<String, String>.putDownloadState() {
        val states = live.downloadStates()
        val entries = playbackQueue.state.value.entries
        val readiness = OfflineReadiness.of(
            entries.map { it.item.item.id },
            stateOf = { id -> states[id] ?: DownloadState.NotDownloaded },
            fetchedAutomatically = { id ->
                entries.firstOrNull { it.item.item.id == id }?.item?.hasAudioOnlyFetch ?: true
            },
        )
        put("downloads.queueReady", readiness.ready.toString())
        put("downloads.queueDownloading", readiness.downloading.toString())
        put("downloads.queueWaiting", readiness.waiting.toString())
        put("downloads.queueUnavailableOffline", readiness.unavailableOffline.toString())
        put("downloads.queueNotAutomatic", readiness.notAutomatic.toString())
        put("downloads.onDisk", states.count { it.value is DownloadState.Downloaded }.toString())
        // Across EVERYTHING, not just the queue: a manual download is invisible to the queue
        // counters above, and "is it fetching anything at all" is the first question a
        // "downloading delayed????" report has to answer.
        put("downloads.running", states.count { it.value is DownloadState.Downloading }.toString())
        put("downloads.maxParallel", QueueAutoDownloader.MAX_PARALLEL.toString())
        // Whether Android is letting this app finish its downloads in the background at all.
        put("downloads.processHeldOpen", DownloadKeepAliveService.holdingProcess.toString())
        // Per item, because a count cannot say whether the one that was TAPPED was there.
        put(
            "downloads.queueStates",
            entries.joinToString(" | ") { entry ->
                val title = entry.item.item.title.take(DIAG_TITLE_CHARS)
                "$title=${states[entry.item.item.id].forDiagnostics()}"
            },
        )
    }

    /**
     * One download state, short enough that ninety of them still fit in a report.
     *
     * A failure keeps a slice of its reason: "members-only" and "network timeout" are the difference
     * between an item that will never be offline and one that will be in a minute.
     */
    private fun DownloadState?.forDiagnostics(): String = when (this) {
        null, DownloadState.NotDownloaded -> "-"
        is DownloadState.Downloaded -> if (audioOnly) "audio" else "full"
        is DownloadState.Downloading -> "fetching${fraction?.let { " ${(it * PERCENT).toInt()}%" } ?: ""}"
        is DownloadState.Failed -> "failed(${reason.take(DIAG_FAILURE_CHARS)})"
    }

    private companion object {
        const val DIAG_TITLE_CHARS = 40

        /** A failure keeps this much of its reason — enough to tell "members-only" from "timeout". */
        const val DIAG_FAILURE_CHARS = 30
        const val PERCENT = 100
    }
}
