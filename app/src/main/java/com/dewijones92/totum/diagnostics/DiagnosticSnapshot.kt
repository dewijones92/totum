package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.downloads.DownloadKeepAliveService
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.pathTaken
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
        /** The account figures already acted on — what makes a surprising resume re-judgeable. */
        val reconciledAccountProgress: () -> Map<MediaItemId, Long>,
        /** The outbox rows that keep failing, worst first — the one risk the new design carries. */
        val stuckAccountUpdates: () -> String,
        val isMetered: () -> Boolean,
    )

    fun capture(): Map<String, String> = buildMap {
        // ONE reading, held for the whole capture: two blocks describing two different queues is
        // the sort of thing that makes a report argue with itself.
        val queue = runCatching { playbackQueue.state.value }.getOrNull()
        val entries = queue?.entries.orEmpty()
        val currentIndex = queue?.currentIndex ?: -1
        runCatching {
            val state = playbackController.state.value
            put("playing.title", state?.title ?: "nothing")
            put("playing.itemId", state?.itemId?.value ?: "-")
            put("playing.kind", state?.kind?.name ?: "-")
            put("playing.positionMs", state?.positionMs?.toString() ?: "-")
            put("playing.hasVideo", state?.hasVideo?.toString() ?: "-")
            // WHICH ROUTE, which a report could not previously answer: "it stopped" reads the same
            // whether the bytes came over SABR, HLS or a plain URL, and those have different causes
            // and different fixes. Derived from the trail rather than stored, so it cannot drift
            // from what actually happened.
            // "-" when nothing is playing, like its neighbours: the trail outlives the play, so an
            // unconditional route reported the LAST play ever made beside `playing.kind=-`. Two
            // different situations producing the same line is the thing this field exists to stop.
            put(
                "playing.route",
                if (state == null) "-" else runCatching { pathTaken() }.getOrElse { "unknown" },
            )
            put("playing.speed", state?.speed?.toString() ?: "-")
            put("playing.skipSilence", state?.skipSilence?.toString() ?: "-")
            put("playing.volumeBoost", state?.volumeBoost?.name ?: "-")
        }
        runCatching {
            put("queue.size", entries.size.toString())
            put("queue.currentIndex", currentIndex.toString())
            put("queue.items", entries.joinToString(" | ") { "${it.item.item.title}" })
        }
        runCatching { putDownloadState(entries) }
        runCatching {
            val settings = live.settings()
            put("settings.playbackMode", settings.playbackMode.name)
            put("settings.autoPlayNext", settings.autoPlayNext.toString())
            put("settings.autoDownloadQueue", settings.autoDownloadQueue.toString())
            put("settings.autoDownloadWifiOnly", settings.autoDownloadWifiOnly.toString())
            put("settings.wifiMaxHeight", settings.wifiMaxHeight.toString())
            put("settings.cellularMaxHeight", settings.cellularMaxHeight.toString())
            // The half that was missing: half the settings never reached a report, so "is SABR on?"
            // and "which categories does it skip?" were unanswerable. Home-server VALUES are
            // never written — a token in a report is an account-takeover risk — only whether each
            // is present, which is the question a torrent report actually asks.
            put("settings.sabrPlayback", settings.sabrPlayback.toString())
            put("settings.mediaFilter", settings.mediaFilter.name)
            put("settings.skipCategories", settings.skipCategories.joinToString(",") { it.id })
            put(
                "settings.homeServer",
                "configured=${settings.homeServerBase.isNotBlank()} " +
                    "signedIn=${settings.homeServerToken.isNotBlank()} " +
                    "prowlarr=${settings.prowlarrApiKey.isNotBlank()}",
            )
        }
        runCatching { putAccountState(entries) }
        runCatching { put("network.metered", live.isMetered().toString()) }
    }

    /** The account block: who is signed in, whether listening reaches them, and what has been acted on. */
    private fun MutableMap<String, String>.putAccountState(entries: List<QueueEntry>) {
        // The account's subscription list, because "it offered me Subscribe to a channel I
        // follow" is unanswerable without knowing how many channels the app thinks it has.
        val subs = accountSubscriptions.channels.value
        put("account.signedIn", accountSubscriptions.signedIn.value.toString())
        // Whether listening is REACHING the account, and how much is waiting to. `NoSession` in the
        // trail was indistinguishable from working for three weeks; this line is the difference.
        val (outbound, pending) = live.accountSync()
        put("yt-sync.outbound", outbound.toString())
        put("yt-sync.pendingUpdates", pending.toString())
        // The count alone cannot say whether the item that was TAPPED is in here, which is
        // the same lesson `downloads.queueStates` was built from — so the queue's own figures
        // are spelled out beside it.
        val reconciled = live.reconciledAccountProgress()
        put("yt-sync.reconciled", reconciled.size.toString())
        put(
            "yt-sync.reconciledInQueue",
            entries
                .mapNotNull { entry ->
                    val id = entry.item.item.id
                    reconciled[id]?.let { "${id.value}=${it}ms" }
                }
                .joinToString(" | ")
                .ifEmpty { "none of the queue" },
        )
        // Nothing is dropped from the outbox any more, so the question a report has to answer is
        // no longer "what was lost" but "what is stuck, and which". A count alone cannot say.
        put("yt-sync.stuck", live.stuckAccountUpdates().ifEmpty { "nothing" })
        put("account.subscriptions", subs.size.toString())
        put("account.subscriptionTitles", subs.joinToString(" | ") { it.title })
    }

    private fun MutableMap<String, String>.putDownloadState(entries: List<QueueEntry>) {
        val states = live.downloadStates()
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
