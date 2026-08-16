package com.dewijones92.totum.innertube.history

import com.dewijones92.totum.innertube.feeds.AccountProgress

/**
 * Reports video watch-progress to YouTube's servers (History + cross-device
 * resume) using YouTube's own stats pings — the account-side counterpart to the
 * app's local resume.
 *
 * This seam **owns where the pings go**, which it did not use to. The tracking URLs were
 * previously handed in by the caller from the extractor's player response; because the
 * extractor runs unauthenticated, those URLs belonged to an anonymous session and every
 * ping credited nobody while still returning HTTP 204. Fetching them here, authenticated,
 * is the fix — and it means a caller cannot supply the wrong ones by mistake.
 */
public interface YouTubeWatchHistory {

    /**
     * Prepares [videoId] for reporting, fetching its account-bearing tracking URLs. Called
     * once per played video, before any progress is reported; a video whose URLs cannot be
     * fetched simply won't sync.
     */
    public suspend fun beginSession(videoId: String)

    /**
     * Reports that [videoId] has been watched to [positionSec] of [lengthSec];
     * [finished] marks it fully watched. No-op when signed out or when no
     * session/tracking is known for the video.
     */
    public suspend fun reportProgress(
        videoId: String,
        positionSec: Float,
        lengthSec: Float,
        finished: Boolean,
    ): WatchHistoryResult

    /**
     * How far the ACCOUNT has watched each video in its recent history — the inbound half, so an
     * item watched on another device resumes here.
     *
     * Keyed by video id. YouTube gives back a **whole-number percentage** — `percentDurationWatched`
     * on a history tile's resume overlay — which the parser turns into a position using the duration
     * from the same tile. Measured 2026-08-16: the app reported 789.873s of a 1:44:13 video and this
     * came back as 13, so on a long video a percent is about a minute. [AccountProgress] carries the
     * duration for exactly that reason, and `resumeFrom` in `:core:domain` is the one place that
     * decides what a number that coarse is allowed to override.
     *
     * Empty when signed out or when the request fails: resuming from what this device knows is
     * always a safe answer, so an inbound failure must never be louder than that.
     */
    public suspend fun watchedPositions(): Map<String, AccountProgress>
}

/** Outcome of a progress report; expected failures are values. */
public sealed interface WatchHistoryResult {
    public data object Success : WatchHistoryResult
    public data object SignedOut : WatchHistoryResult

    /** No tracking URLs registered for the video (e.g. non-YouTube, or capture failed). */
    public data object NoSession : WatchHistoryResult
    public data class Failure(val detail: String) : WatchHistoryResult
}
