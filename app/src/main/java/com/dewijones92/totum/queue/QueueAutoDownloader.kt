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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches the **audio** of everything in the queue, so the queue is listenable
 * offline without being asked. Videos take their audio-only stream (small and
 * quick); podcasts take their enclosure, which is already audio.
 *
 * A **bounded** number run at once ([maxParallel] lanes). The whole queue is downloaded, so
 * firing every item at once saturates the connection and starves playback of bandwidth — report
 * 0.1.313 caught nine crawling together — while one at a time drains a long queue far too slowly
 * (Dewi, 2026-08-31: *"background downloading should work for multiple files in parallel"*). A few
 * lanes is the middle, and the limit is the part that matters.
 *
 * Nothing is ever deleted automatically: leaving the queue keeps the file, and it is removed from
 * Library like any other download.
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
    /**
     * How many downloads may be in flight at once.
     *
     * Not "as many as there are": these compete with playback for one connection, and the
     * failure that made this sequential in the first place was nine of them crawling.
     */
    private val maxParallel: Int = MAX_PARALLEL,
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
    private val attempts = ConcurrentHashMap<MediaItemId, Int>()

    fun start() {
        scope.launch {
            // collectLatest, not collect: a pass over a long queue can take many minutes, and
            // `collect` made every queue change wait for it. So dragging the thing you want next to
            // the top of the queue had no effect until the pass in flight finished -- which is the
            // one moment the ordering matters. Cancelling a pass loses nothing: the downloads
            // themselves live in the manager's own scope and carry on, and the next pass skips
            // whatever is already in flight.
            queue.collectLatest { snapshot -> pass(snapshot) }
        }
    }

    /**
     * One sweep of the queue, [maxParallel] items at a time.
     *
     * Lanes claim items from one ordered channel, so queue order — and the playing item's place at
     * the front of it — is still honoured: a lane takes the next item nobody has taken.
     */
    private suspend fun pass(snapshot: QueueSnapshot) {
        if (refused()) return
        val states = downloads.observeDownloads().first()
        // Ordering only -- nothing is skipped and no budget changes. The playing item simply
        // goes to the front of the pass.
        val playing = playingNow()
        val ordered = if (playing == null) {
            snapshot.entries
        } else {
            snapshot.entries.sortedBy { if (it.item.item.id == playing) 0 else 1 }
        }
        if (ordered.isEmpty()) return
        val lanes = minOf(maxParallel, ordered.size)
        val waiting = Channel<QueueEntry>(Channel.UNLIMITED)
        ordered.forEach { waiting.trySend(it) }
        waiting.close()
        val started = AtomicInteger()
        // Announced only when there is something outstanding AND the picture has changed since the
        // last announcement. The queue emits on every edit and every advance, often twice for one
        // action -- three links shared in a row produced six of these -- and in the steady state,
        // the whole queue already on disk, a line per emission would be the loudest thing in a
        // bounded report while saying nothing at all.
        val outstanding = ordered.count { states[it.item.item.id] !is DownloadState.Downloaded }
        if (outstanding > 0 && outstanding to ordered.size != lastAnnounced) {
            lastAnnounced = outstanding to ordered.size
            Diag.log(
                "download",
                "pass over $outstanding outstanding of ${ordered.size} queued item(s), up to $lanes at a time",
            )
        }
        coroutineScope {
            repeat(lanes) {
                launch {
                    for (entry in waiting) {
                        // Read before EVERY item, not once at the top of the pass: read once, a
                        // queue that started on Wi-Fi carried on over mobile data for the rest of
                        // the pass, and switching the setting off only took effect on the next one.
                        if (refused()) return@launch
                        started.incrementAndGet()
                        download(entry, states)
                    }
                }
            }
        }
        if (started.get() > 0) {
            Diag.log("download", "pass done: asked for ${started.get()} of ${ordered.size} queued item(s)")
        }
    }

    /** What the last announced pass looked like, so back-to-back identical passes say it once. */
    private var lastAnnounced: Pair<Int, Int>? = null

    /** The gate's last answer, so a refusal is said once rather than on every queue change. */
    private var lastRefusal: String? = null

    /**
     * Whether automatic downloading is allowed right this second — the setting and the network.
     *
     * Logged only when the answer CHANGES: the queue emits on every edit, and repeating
     * "automatic downloads are switched off" on each one buries the trail in a fact that has not
     * moved. The change back to allowed is logged too, so the trail says when fetching resumed.
     */
    private fun refused(): Boolean {
        val reason = when {
            !isEnabled() -> "automatic downloads are switched off"
            !isAllowedOnThisNetwork() -> "this network is not allowed for automatic downloads"
            else -> null
        }
        if (reason != lastRefusal) {
            Diag.log("download", reason?.let { "not fetching: $it" } ?: "fetching is allowed again")
            lastRefusal = reason
        }
        return reason != null
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
     * This is what BOUNDS the parallelism. `DownloadManager.download` launches into its own scope
     * and returns at once, so without waiting here a lane would claim every remaining item in a
     * tight loop and the limit would mean nothing — report 0.1.313 shows what that looked like:
     * nine running together, each crawling, with the app unable to say anything useful about any of
     * them. A lane holds its item until it settles, so at most [maxParallel] are ever in flight.
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
    private val explained: MutableSet<MediaItemId> = ConcurrentHashMap.newKeySet()

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

    internal companion object {
        /** Transient retries per item per session — enough for a blip, not a loop. */
        const val MAX_ATTEMPTS = 3

        /**
         * Downloads in flight at once.
         *
         * Three, not "lots": these are background fetches sharing one connection with whatever is
         * playing, and the reason this was ever one-at-a-time is that unbounded parallelism left
         * nine crawling and none finishing (0.1.313). Three drains a long queue several times
         * faster while leaving playback most of the pipe.
         */
        const val MAX_PARALLEL = 3

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
