package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.resumeFrom
import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * **Cached for a session.** The history is one request that answers for every recent video, so
 * fetching it per play would be a network round trip in front of every tap for a number that
 * barely moves. Refreshed on demand after [staleAfterMs].
 */
class AccountResumePositions(
    private val local: suspend (MediaItemId) -> Long?,
    private val history: YouTubeWatchHistory,
    private val now: () -> Long = System::currentTimeMillis,
    private val staleAfterMs: Long = STALE_AFTER_MS,
) {
    private val guard = Mutex()
    private var watched: Map<String, AccountProgress> = emptyMap()
    private var fetchedAtMs = 0L

    /** The position to start [itemId] at, or null for the beginning. */
    suspend fun resumePositionMs(itemId: MediaItemId): Long? {
        val localMs = local(itemId)
        val remote = remoteFor(itemId)
        val choice = resumeFrom(localMs, remote?.positionMs, remote?.durationMs)
        // The inputs, not just the outcome — a surprising resume must be re-judgeable from a report
        // without anyone having to guess which side won or why.
        Diag.log(
            "yt-sync",
            "resume ${itemId.value} at ${choice.positionMs ?: 0}ms — ${choice.because} " +
                "[local=${localMs ?: "none"} youtube=${remote?.positionMs ?: "none"} " +
                "of ${remote?.durationMs ?: "unknown"}]",
        )
        return choice.positionMs
    }

    private suspend fun remoteFor(itemId: MediaItemId): AccountProgress? {
        guard.withLock {
            if (watched.isEmpty() || now() - fetchedAtMs > staleAfterMs) {
                watched = runCatching { history.watchedPositions() }.getOrElse {
                    Diag.warn("yt-sync", "could not read YouTube's watched positions", it)
                    emptyMap()
                }
                fetchedAtMs = now()
            }
        }
        return watched[itemId.value]
    }

    private companion object {
        /** A watched position moves on the timescale of watching something, not of tapping one. */
        const val STALE_AFTER_MS = 5 * 60 * 1_000L
    }
}
