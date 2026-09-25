package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Because
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.NoReconciledAccountProgress
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.ReconciledAccountProgress
import com.dewijones92.totum.domain.resumeFrom
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where an item resumes, once YouTube's opinion is taken into account as well as this device's.
 *
 * Dewi asked for two-way progress sync (2026-07-25). The outbound half already worked — and is now
 * measured rather than assumed: the app reported `caVJh4jrOxE` at 789.873s and YouTube's history
 * came back holding 13% of a 1:44:13 video. Nothing read a position *back*, so watching forty
 * minutes on another device and opening Totum started from nothing.
 *
 * Wrapped around the one lambda the launcher already resumes from, rather than added beside it: a
 * second source of "where does this start" is exactly the shape of bug this app keeps finding
 * (`routeNow`, `MediaItem.pillar`, `mediaFacts`). Podcasts fall through untouched — YouTube has no
 * opinion about them, so the rule returns the local answer.
 *
 * **Never waits on the network for long, and never offline.** This sits on the play path — `play()`
 * asks for the resume position before it touches the player — and until 2026-09-06 it asked YouTube
 * first, unbounded. Report 0.1.477 (30 Aug, no network): every play waited 7s on a DNS failure
 * before its transition, and the last six plays — the item that "would not play", tapped six times
 * — never transitioned at all, because the read hung. So the read is bounded by [remoteWaitMs]
 * (a healthy `FEhistory` answers in ~300ms) and skipped outright when [offline] says so; a read that
 * does not make the cut keeps loading in [scope] for the next play and for the rows.
 *
 * **Cached for a session.** The history is one request that answers for every recent video, so
 * fetching it per play would be a network round trip in front of every tap for a number that
 * barely moves. Refreshed on demand after [staleAfterMs].
 *
 * **And every remote number used is written down** ([reconciled]), because a number that cannot
 * move must not keep winning. Report 0.1.496: YouTube held one video at 77700ms with the outbound
 * half refused, so six rewinds in a row were overruled by the same stale figure.
 *
 * When the account's figure wins it is also **adopted** into this device's own store ([adopt]), so
 * "already acted on" is a fact rather than an assumption. Without that, resuming from the TV's
 * position and closing the app two seconds later — before any tick, pause or seek could save —
 * recorded the figure as used while this device still held its old one, and the next tap would
 * answer with the old one and never offer the TV's again. Adopting it makes the two agree, so the
 * answer is the same either way.
 */
class AccountResumePositions(
    /**
     * Everything this device knows about the item — the position AND whether it is finished.
     *
     * A position alone is not enough: "finished here" and "never played here" both arrive as no
     * position, and they want opposite answers from the account. Asking one source for the whole
     * answer, rather than a second cached one for the missing bit, is the point.
     */
    private val local: suspend (MediaItemId) -> PlayState,
    /** Writes the account's figure into this device's own store when it wins — see the class note. */
    private val adopt: suspend (MediaItemId, Long, Long?) -> Unit = { _, _, _ -> },
    private val history: YouTubeWatchHistory,
    private val scope: CoroutineScope,
    private val reconciled: ReconciledAccountProgress = NoReconciledAccountProgress,
    private val offline: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = STALE_AFTER_MS,
    private val remoteWaitMs: Long = REMOTE_WAIT_MS,
) {
    private val guard = Mutex()
    private val _watched = MutableStateFlow<Map<String, AccountProgress>>(emptyMap())
    private var fetchedAtMs = 0L
    private var inFlight: Deferred<Unit>? = null

    /**
     * The account's watched positions, live — what lets ROWS show progress made elsewhere, not only
     * the resume of a tap. Report 0.1.477 (22 Aug): a video half-watched on the website showed
     * nothing in Totum's lists, because this map was only ever consulted at the moment of playing.
     */
    val watched: StateFlow<Map<String, AccountProgress>> = _watched

    /** The position to start [itemId] at, or null for the beginning. */
    suspend fun resumePositionMs(itemId: MediaItemId): Long? {
        val here = local(itemId)
        // The same rule the ROWS already use: "a local Played is final — exact and deliberate, a
        // rounded percent cannot un-play it" (`accountAwarePlayState`). The resume path could not
        // apply it while it only received a position, so a video finished on this phone still
        // jumped to YouTube's stale 30% while the row beside it said Played. Restarting a
        // finished item is the app's own rule, and the tap and the bar now agree about it. Mark
        // it unplayed and the account's figure gets a fresh hearing — nothing is recorded here.
        if (here is PlayState.Played) {
            Diag.log(
                "yt-sync",
                "resume ${itemId.value} from the start — finished here, whatever the account says " +
                    "[youtube=${_watched.value[itemId.value]?.positionMs ?: "none"}]",
            )
            return null
        }
        val localMs = (here as? PlayState.InProgress)?.positionMs
        val (remote, note) = remoteFor(itemId)
        val alreadyUsedMs = reconciled.reconciledMs(itemId)
        val choice = resumeFrom(localMs, remote?.positionMs, remote?.durationMs, alreadyUsedMs)
        // The inputs, not just the outcome — a surprising resume must be re-judgeable from a report
        // without anyone having to guess which side won or why, including whether the account was
        // even asked and whether its number had moved since last time.
        Diag.log(
            "yt-sync",
            "resume ${itemId.value} at ${choice.positionMs ?: 0}ms — ${choice.because} " +
                "[local=${localMs ?: "none"} youtube=${remote?.positionMs ?: "none"} " +
                "of ${remote?.durationMs ?: "unknown"} alreadyUsed=${alreadyUsedMs ?: "none"}]" +
                (note?.let { " ($it)" } ?: ""),
        )
        act(itemId, remote, alreadyUsedMs, choice.because)
        return choice.positionMs
    }

    /**
     * Writes down the account's figure, and — when it WON — adopts it as this device's position.
     *
     * Adopting first, and on the winning test rather than on the two numbers happening to match:
     * a figure recorded as acted on while this device still holds an older position is how thirty
     * minutes of progress made on the TV went missing on the very next tap.
     */
    private suspend fun act(
        itemId: MediaItemId,
        remote: AccountProgress?,
        alreadyUsedMs: Long?,
        because: Because,
    ) {
        val remoteMs = remote?.positionMs ?: return
        if (remoteMs == alreadyUsedMs) return
        val remoteWon = when (because) {
            Because.ONLY_REMOTE, Because.REMOTE_IS_AHEAD -> true
            Because.ONLY_LOCAL, Because.LOCAL_IS_AS_GOOD, Because.REMOTE_IS_OLD_NEWS -> false
            Because.REMOTE_SAYS_FINISHED -> false
            Because.REMOTE_ONLY_SAYS_STARTED -> {
                Diag.log(
                    "yt-sync",
                    "youtube=${remoteMs}ms for ${itemId.value} is YouTube's 10% floor, so it is not recorded " +
                        "as acted on [kept alreadyUsed=${alreadyUsedMs ?: "none"}]",
                )
                return
            }
        }
        if (remoteWon) adopt(itemId, remoteMs, remote.durationMs)
        reconciled.reconcile(itemId, remoteMs)
        val outcome = when {
            remoteWon -> " and adopted as this device's position"
            because == Because.REMOTE_SAYS_FINISHED -> ", and it plays again from the beginning"
            else -> ", but this device's won"
        }
        Diag.log(
            "yt-sync",
            "youtube=${remoteMs}ms for ${itemId.value} is now acted on (was ${alreadyUsedMs ?: "none"})$outcome"
        )
    }

    /** Re-reads the account's history if stale, waiting for it — for the rows, off the play path. */
    suspend fun refresh() {
        refreshing()?.join()
    }

    private suspend fun remoteFor(itemId: MediaItemId): Pair<AccountProgress?, String?> {
        if (offline()) return _watched.value[itemId.value] to "offline, so the account was not asked"
        val fetch = refreshing()
        val note = if (fetch != null && withTimeoutOrNull(remoteWaitMs) { fetch.join() } == null) {
            "the account did not answer within ${remoteWaitMs}ms, so it was not waited for; it keeps loading"
        } else {
            null
        }
        return _watched.value[itemId.value] to note
    }

    /**
     * The fetch to wait on when what is held is stale, or null when it is fresh. One in flight at a
     * time: a second caller joins the first rather than asking YouTube twice.
     */
    private suspend fun refreshing(): Deferred<Unit>? = guard.withLock {
        if (_watched.value.isNotEmpty() && now() - fetchedAtMs <= staleAfterMs) return@withLock null
        inFlight?.takeIf { it.isActive } ?: scope.async {
            val result = runCatching { history.watchedPositions() }.getOrElse {
                Diag.warn("yt-sync", "could not read YouTube's watched positions", it)
                emptyMap()
            }
            guard.withLock {
                _watched.value = result
                fetchedAtMs = now()
                inFlight = null
            }
        }.also { inFlight = it }
    }

    companion object {
        /** A watched position moves on the timescale of watching something, not of tapping one. */
        private const val STALE_AFTER_MS = 5 * 60 * 1_000L

        /**
         * How long a play may wait for the account. A healthy `FEhistory` answers in about 300ms
         * (16:40:06.289 → .550 in report 0.1.477); a sick network does not answer at all, and the
         * difference between them is the difference between the next item playing and it not.
         */
        const val REMOTE_WAIT_MS = 1_500L
    }
}
