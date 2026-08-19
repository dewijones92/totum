package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.isPermanent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetches the **audio** of everything in the queue, so the queue is listenable
 * offline without being asked. Videos take their audio-only stream (small and
 * quick); podcasts take their enclosure, which is already audio.
 *
 * Sequential on purpose — the whole queue is downloaded, and a long queue firing
 * every download at once would saturate the connection and starve playback of
 * bandwidth. Nothing is ever deleted automatically: leaving the queue keeps the
 * file, and it is removed from Library like any other download.
 */
class QueueAutoDownloader(
    private val queue: StateFlow<QueueSnapshot>,
    private val downloads: DownloadManager,
    private val scope: CoroutineScope,
    private val isEnabled: () -> Boolean,
    private val isAllowedOnThisNetwork: () -> Boolean,
    /**
     * Whether an item can be fetched as AUDIO, which is the only thing this class exists to do.
     *
     * False for a torrent: the home server's audio-only version is an HLS playlist, which a plain
     * download cannot store as one file, so `audioOnly = true` silently fetched the whole film
     * instead — confirmed on a device 2026-08-06, where a torrent requested audio-only was recorded
     * `copy=full`. A queue of films would then fill the phone, roughly seven times over per item,
     * to deliver something nobody asked for. A deliberate tap on Download still fetches it.
     *
     * Supplied by AppContainer, where all pillar routing lives, so this class stays pillar-agnostic.
     */
    private val fetchesAudioOnly: (PlayableItem) -> Boolean = { true },
    /**
     * What is playing right now, which is fetched before the rest of the queue.
     *
     * Not the cursor: a PEEK leaves `currentIndex` at -1 by design, and a peek is precisely how the
     * reported case arose. Reported from a real device (0.1.435): a Ms Rachel video he was 52 minutes
     * into sat first in a 47-item queue with NO download event at all, while the downloader ground
     * through a podcast that was 403ing and retrying.
     *
     * The pass is strictly in order, one at a time, each awaited for up to [settleTimeoutMs] -- so one
     * slow or retrying item blocks everything behind it, including the one item where a download has
     * immediate value: what is playing is what the rescue ladder falls back to when the stream stalls.
     */
    private val playingNow: () -> MediaItemId? = { null },
    private val maxAttempts: Int = MAX_ATTEMPTS,
    /** How long one download may hold the queue before the next starts anyway. */
    private val settleTimeoutMs: Long = SETTLE_TIMEOUT_MS,
    /** Multiplied by the retry number, so the second waits twice as long as the first. */
    private val retryBackoffMs: Long = RETRY_BACKOFF_MS,
) {
    /**
     * Transient attempts per item this session, so a flaky connection gets a few more goes
     * and a broken item does not get infinite ones. Not persisted: a fresh launch is a fair
     * reason to try again, and a permanent failure is refused on its reason regardless.
     */
    private val attempts = mutableMapOf<MediaItemId, Int>()

    fun start() {
        scope.launch {
            queue.collect { snapshot ->
                if (!isEnabled() || !isAllowedOnThisNetwork()) return@collect
                val states = downloads.observeDownloads().first()
                // Ordering only -- nothing is skipped and no budget changes. The playing item simply
                // goes to the front of the pass.
                val playing = playingNow()
                val ordered = if (playing == null) {
                    snapshot.entries
                } else {
                    snapshot.entries.sortedBy { if (it.item.item.id == playing) 0 else 1 }
                }
                ordered.forEach { entry -> download(entry, states) }
            }
        }
    }

    /**
     * Fetches one entry, and keeps going while a failure is worth another attempt.
     *
     * The loop is the point. The retry budget was here from the start and there was nothing to
     * spend it: the only thing that ever re-examined a failure was the next `queue.collect`
     * emission, so a download that failed sat failed until the queue itself changed. Report
     * 0.1.390 — Dewi's *"downloading delayed????"* — is that exactly: the tennis podcast 403'd at
     * 20:58:31, and the attempt that worked came at 20:58:37 only because he happened to reorder
     * an entry. Nothing else would have asked again this session.
     */
    private suspend fun download(entry: QueueEntry, states: Map<*, DownloadState>) {
        val item = entry.item.item
        var state = states[item.id]
        while (true) {
            val skip = skipReason(entry, state)
            if (skip != null) {
                // Said ONCE per item, not on every queue change. Three permanently-failed
                // members-only videos repeated their reason on every pass and took 14% of a
                // 387-event report (0.1.229) saying nothing new — and the report buffer is
                // bounded, so noise like that is evidence thrown away.
                if (skip.isNotEmpty() && explained.add(item.id)) {
                    Diag.log("download", "not fetching \"${item.title}\": $skip")
                }
                return
            }
            // Reaching here with a failure in hand means skipReason has just decided this one is
            // worth another go, and counted it. The retry number and the reason both go in the
            // trail, because "it downloaded" and "it downloaded on the third go, 30 seconds late"
            // are otherwise the same line — and the delay was the whole complaint.
            (state as? DownloadState.Failed)?.let { failure ->
                val retry = attempts.getOrDefault(item.id, 1)
                Diag.warn(
                    "download",
                    "retrying \"${item.title}\" (retry $retry of $maxAttempts) " +
                        "after ${failure.reason.take(REASON_CHARS)}",
                )
                // A retry with no gap is not a retry: a signed URL that has just answered 403
                // answers 403 again immediately. His successful attempt came six seconds later.
                delay(retryBackoffMs * retry)
            }
            // The whole entry, handle included, so the video route gets its watch URL.
            downloads.download(entry.item, audioOnly = true)
            state = awaitSettled(item.id) ?: return
            if (state !is DownloadState.Failed) return
        }
    }

    /**
     * Waits for one download to finish before the next is started.
     *
     * The class has always CLAIMED to be sequential and never was: `DownloadManager.download`
     * launches into its own scope and returns at once, so this loop fired the entire queue in one
     * go — report 0.1.313 shows nine running together, each crawling, with the app unable to say
     * anything useful about any of them. Sequential is the right shape here because these are
     * background fetches competing with playback for the same connection.
     *
     * Only the AUTOMATIC path waits. A download somebody asked for by tapping still starts
     * immediately rather than queueing behind seventy of these.
     */
    private suspend fun awaitSettled(id: MediaItemId): DownloadState? {
        val settled = withTimeoutOrNull(settleTimeoutMs) {
            downloads.observeDownloads().first { states -> states[id] !is DownloadState.Downloading }
        }
        if (settled == null) {
            // BOUNDED, because waiting is now the thing that could break this. A download whose
            // flow never reaches a terminal state would otherwise hold the queue for the life of
            // the process — every remaining item stuck behind one, which is strictly worse than
            // the unbounded parallelism this replaced. Move on and say so.
            Diag.warn("download", "gave up waiting for $id after ${settleTimeoutMs}ms; moving on")
        }
        // Null means "I do not know how it ended", which is not the same as a failure and must
        // never be retried on: a wedged download retried is two wedged downloads.
        return settled?.get(id)
    }

    /** Items whose skip reason has already been logged; it does not change between passes. */
    private val explained = mutableSetOf<MediaItemId>()

    /**
     * Null to fetch it; a reason to skip. An empty reason means "ordinary, not worth a line" —
     * already downloaded, nothing to fetch yet — so the trail keeps only the skips that would
     * otherwise be mysterious.
     */
    private fun skipReason(entry: QueueEntry, state: DownloadState?): String? = when {
        // A local file is already the point of this; nothing to fetch.
        entry.item.handle is PlayHandle.LocalVideo -> ""
        state is DownloadState.Downloaded || state is DownloadState.Downloading -> ""
        // Said once per item, and worth saying: silence here would read as a broken auto-download.
        !fetchesAudioOnly(entry.item) ->
            "there is no audio-only fetch for it, and fetching the whole thing is not this feature"
        // Nothing to fetch yet — a feed item whose enclosure hasn't been read. Asking
        // anyway would only record a failure against it.
        entry.item.fetchUrl == null -> ""
        state is DownloadState.Failed -> failureSkip(entry.item.item.id, state)
        else -> null
    }

    /**
     * Whether a previous failure should stop us asking again.
     *
     * This used to fall through and retry on EVERY queue change, so two members-only videos
     * in a 59-item queue were re-attempted on every launch for days — in every diagnostics
     * report sent on 2026-07-28.
     */
    private fun failureSkip(id: MediaItemId, state: DownloadState.Failed): String? = when {
        state.isPermanent -> "asking again cannot help — ${state.reason.take(REASON_CHARS)}"
        else -> {
            val used = attempts.getOrDefault(id, 0)
            if (used >= maxAttempts) {
                "gave up after $used attempts"
            } else {
                attempts[id] = used + 1
                null
            }
        }
    }

    private companion object {
        /** Transient retries per item per session — enough for a blip, not a loop. */
        const val MAX_ATTEMPTS = 3

        /**
         * Long enough for a real audio fetch on a poor connection, short enough that a wedged
         * one costs minutes rather than the session.
         */
        const val SETTLE_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Long enough for the retry to have a real chance, short enough that a failing item does
         * not hold a long queue for minutes. Multiplied by the retry number: 5s, then 10s, 15s.
         * His successful retry landed six seconds after the 403.
         */
        const val RETRY_BACKOFF_MS = 5_000L

        /**
         * Extractor errors are long; this is enough to recognise one in the trail — and enough to
         * keep the status code, which is the part that says whether asking again can help.
         * `Network(detail=ERROR: unable to download video data: HTTP Error 403: Forbidden)` is 78.
         */
        const val REASON_CHARS = 120
    }
}
