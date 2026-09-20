package com.dewijones92.totum.innertube.history.fake

import com.dewijones92.totum.innertube.feeds.AccountProgress
import com.dewijones92.totum.innertube.history.SessionResult
import com.dewijones92.totum.innertube.history.WatchHistoryResult
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import kotlinx.coroutines.CompletableDeferred

/** In-memory [YouTubeWatchHistory] for tests and previews; records each call. */
public class FakeYouTubeWatchHistory(
    /** Mutable so a test can change the outcome mid-run, which is the interesting case. */
    public var result: WatchHistoryResult = WatchHistoryResult.Success,
) : YouTubeWatchHistory {

    public data class Report(
        val videoId: String,
        val positionSec: Float,
        val lengthSec: Float,
        val finished: Boolean,
    )

    /** Sessions actually OPENED — one entry per video, like the real adapter's cache. */
    public val sessions: MutableList<String> = mutableListOf()

    /** Every ASK, including the ones answered from the cache — so a retry is distinguishable. */
    public val sessionAttempts: MutableList<String> = mutableListOf()
    public val reports: MutableList<Report> = mutableListOf()

    /** Videos this fake refuses a session for, so a test can strand one row behind others. */
    public val notTrackable: MutableSet<String> = mutableSetOf()

    /** What [beginSession] answers for everything not in [notTrackable] or [sessionResults]. */
    public var sessionResult: SessionResult = SessionResult.Opened

    /** Per-video answers, for a test that needs some rows to fail and others not to. */
    public val sessionResults: MutableMap<String, SessionResult> = mutableMapOf()

    /**
     * Models the real adapter's own cache: asking twice for a session it already holds costs
     * nothing and is not a second open. Without that, a caller that correctly asks every time
     * (because the adapter is the one place that should remember) looks like it is opening
     * sessions twice.
     */
    override suspend fun beginSession(videoId: String): SessionResult {
        sessionAttempts += videoId
        if (videoId in sessions) return SessionResult.Opened
        val result = sessionResults[videoId]
            ?: if (videoId in notTrackable) SessionResult.NotTrackable else sessionResult
        if (result is SessionResult.Opened) sessions += videoId
        return result
    }

    /** Sessions dropped because the account changed — a test can assert it happened. */
    public var forgottenSessions: Int = 0
        private set

    override suspend fun forgetSessions() {
        forgottenSessions++
        sessions.clear()
    }

    override suspend fun reportProgress(
        videoId: String,
        positionSec: Float,
        lengthSec: Float,
        finished: Boolean,
    ): WatchHistoryResult {
        // Like the adapter: `sessions[videoId] ?: return NoSession`. Without it the fake could not
        // reproduce the failure that shipped last round — a session opened, dropped on sign-out,
        // and then reported against — which is precisely the shape a double has to be able to
        // fail in to be covering anything.
        if (videoId !in sessions) return WatchHistoryResult.NoSession
        reports += Report(videoId, positionSec, lengthSec, finished)
        return result
    }

    /** What YouTube "knows", for tests driving the inbound half. */
    public var watched: Map<String, AccountProgress> = emptyMap()

    /** How many times it was asked — the inbound read is cached, and that has to be provable. */
    public var watchedCalls: Int = 0
        private set

    /** Makes the inbound read fail, so a test can prove the fall-back to the local position. */
    public var failWatched: Boolean = false

    /**
     * Makes the inbound read HANG until completed — the shape that mattered on a real phone: with no
     * network, the read neither answered nor failed, and every play waited on it (report 0.1.477).
     */
    public var watchedGate: CompletableDeferred<Unit>? = null

    override suspend fun watchedPositions(): Map<String, AccountProgress> {
        watchedCalls++
        watchedGate?.await()
        if (failWatched) error("no network")
        return watched
    }
}
