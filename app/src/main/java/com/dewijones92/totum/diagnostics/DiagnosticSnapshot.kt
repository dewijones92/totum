package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.downloads.DownloadKeepAliveService
import com.dewijones92.totum.playback.PlaybackCacheReport
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.pathTaken
import com.dewijones92.totum.queue.GroupDownloads
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
        val exsurge: () -> Map<String, String> = { emptyMap() },
        val appUpdate: () -> Map<String, String> = { emptyMap() },
        val polite: () -> Map<String, String> = { emptyMap() },
        val backup: () -> Map<String, String> = { emptyMap() },
        val groupDownloads: () -> GroupDownloads? = { null },
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
            put("playing.facts", playingFacts(playbackQueue.nowPlaying.value?.item, state))
        }
        runCatching {
            put("queue.size", entries.size.toString())
            put("queue.currentIndex", currentIndex.toString())
            put(
                "queue.place",
                queue?.anchor?.let { "after an excursion: ${it.value} (index ${queue.place})" } ?: "the cursor"
            )
            put("queue.items", listedAround(entries, currentIndex, LISTED_BEFORE, LISTED_AFTER) { it.item.item.title })
            put(
                "queue.groups",
                groupRunsAround(entries.map { it.group?.title }, currentIndex, LISTED_BEFORE, LISTED_AFTER)
            )
        }
        runCatching { putDownloadState(entries, currentIndex) }
        runCatching { putSettings(live.settings()) }
        runCatching { putAccountState(entries) }
        runCatching { put("network.metered", live.isMetered().toString()) }
        runCatching { putAll(live.exsurge()) }
        runCatching { putAll(live.appUpdate()) }
        runCatching { putAll(live.polite()) }
        runCatching { putAll(live.backup()) }
        runCatching { putAll(PlaybackCacheReport.state()) }
    }

    private fun MutableMap<String, String>.putSettings(settings: AppPreferences.Settings) {
        put("settings.playbackMode", settings.playbackMode.name)
        put("settings.silenceMode", settings.silenceMode.name)
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
        put("settings.feedHiddenUntilAsked", settings.feedHiddenUntilAsked.toString())
        put("settings.gaplessQueue", settings.gaplessQueue.toString())
        put("settings.exploreByDefault", settings.exploreByDefault.toString())
        put("settings.exploreCap", if (settings.exploreCap > 0) settings.exploreCap.toString() else "no limit")
        put("settings.rewindOnResume", settings.rewindOnResume.toString())
        put("settings.silentTailEnds", settings.silentTailEnds.toString())
        put(
            "settings.inbox",
            if (settings.inboxAll) "every subscription" else "${settings.inboxSources.size} source(s)"
        )
        put(
            "settings.sourceSkips",
            settings.sourceSkips.entries.joinToString(" | ") { (id, s) ->
                "${id.value} intro=${s.introEndMs ?: "-"}ms outro=${s.outroLengthMs ?: "-"}ms"
            }.ifEmpty { "none" },
        )
        put("settings.skipCategories", settings.skipCategories.joinToString(",") { it.id })
        put(
            "settings.homeServer",
            "configured=${settings.homeServerBase.isNotBlank()} " +
                "signedIn=${settings.homeServerToken.isNotBlank()} " +
                "prowlarr=${settings.prowlarrApiKey.isNotBlank()}",
        )
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

    private fun MutableMap<String, String>.putDownloadState(entries: List<QueueEntry>, currentIndex: Int) {
        val states = live.downloadStates()
        val byId = entries.associateBy { it.item.item.id }
        val readiness = OfflineReadiness.of(
            entries.map { it.item.item.id },
            stateOf = { id -> states[id] ?: DownloadState.NotDownloaded },
            fetchedAutomatically = { id ->
                byId[id]?.let { entry ->
                    live.groupDownloads()?.fetchesAutomatically(entry) ?: entry.item.hasAudioOnlyFetch
                } ?: true
            },
        )
        val choices = live.groupDownloads()?.choices?.value.orEmpty()
        put(
            "downloads.groups",
            choices.entries.joinToString(" | ") { (id, choice) ->
                "$id=$choice (${entries.count { it.group?.id == id }})"
            }.ifEmpty { "none" },
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
            listedAround(entries, currentIndex, LISTED_BEFORE, LISTED_AFTER) { entry ->
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
        const val LISTED_BEFORE = 50
        const val LISTED_AFTER = 200

        /** A failure keeps this much of its reason — enough to tell "members-only" from "timeout". */
        const val DIAG_FAILURE_CHARS = 30
        const val PERCENT = 100
    }
}

internal fun <T> listedAround(
    entries: List<T>,
    current: Int,
    before: Int,
    after: Int,
    describe: (T) -> String,
): String {
    val from = (current - before).coerceIn(0, entries.size)
    val to = (maxOf(current, 0) + after).coerceIn(from, entries.size)
    val shown = entries.subList(from, to).joinToString(" | ", transform = describe)
    if (from == 0 && to == entries.size) return shown
    return "[items ${from + 1}-$to of ${entries.size}, around the playing one] $shown"
}

internal fun groupRunsAround(groups: List<String?>, current: Int, before: Int, after: Int): String {
    val from = (current - before).coerceIn(0, groups.size)
    val to = (maxOf(current, 0) + after).coerceIn(from, groups.size)
    val runs = mutableListOf<Triple<Int, Int, String?>>()
    for (i in from until to) {
        val last = runs.lastOrNull()
        if (last != null && last.third == groups[i]) {
            runs[runs.lastIndex] = last.copy(second = i)
        } else {
            runs += Triple(i, i, groups[i])
        }
    }
    val listed = runs.joinToString(" · ") { (start, end, title) ->
        val span = if (start == end) "${start + 1}" else "${start + 1}-${end + 1}"
        val playing = if (current in start..end) " (playing ${current + 1})" else ""
        "$span ${title ?: "added by you"}$playing"
    }
    return "[items ${from + 1}-$to of ${groups.size}] $listed"
}

private fun playingFacts(listed: MediaItem?, state: PlaybackState?): String {
    val queued = listOf(
        "published=${listed?.publishedAt ?: listed?.publishedText ?: "-"}",
        "views=${listed?.viewsText ?: "-"}",
        "length=${listed?.duration ?: "-"}",
        "by=${listed?.author ?: "-"}",
    )
    val player = listOf(
        "published=${state?.publishedAt ?: state?.publishedText ?: "-"}",
        "views=${state?.viewsText ?: "-"}",
        "description=${state?.description?.length ?: 0} chars",
    )
    return "queued item: ${queued.joinToString(" ")}; player: ${player.joinToString(" ")}"
}
