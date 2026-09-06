package com.dewijones92.totum.innertube.history.fake

import com.dewijones92.totum.innertube.feeds.AccountProgress
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

    public val sessions: MutableList<String> = mutableListOf()
    public val reports: MutableList<Report> = mutableListOf()

    override suspend fun beginSession(videoId: String) {
        sessions += videoId
    }

    override suspend fun reportProgress(
        videoId: String,
        positionSec: Float,
        lengthSec: Float,
        finished: Boolean,
    ): WatchHistoryResult {
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
