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
     * once per played video, before any progress is reported.
     *
     * The OUTCOME matters, and used to be thrown away. "YouTube would not answer at all" and "this
     * particular video has no tracking in a perfectly good answer" are different facts, and
     * collapsing them is what left 123 updates stuck behind three videos in report 0.1.496: the
     * drain read one video's verdict as a verdict on the sender and stopped, every pass, for ever.
     */
    public suspend fun beginSession(videoId: String): SessionResult

    /**
     * Drops every tracking session held for the previous account.
     *
     * The URLs are identity-bearing — the authenticated one differs by a `uga` parameter, which is
     * the signed-in signal — and a session is reused for the life of the process without being
     * revalidated. Without this, signing out and back in leaves a video pinging the OLD account's
     * URL while carrying the new account's token.
     */
    public suspend fun forgetSessions()

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

    /**
     * YouTube answered about this video and its answer carried no tracking — so this ONE video
     * cannot be reported, however healthy the sender is. Anything else queued behind it still can.
     */
    public data object NotTrackable : WatchHistoryResult
    public data class Failure(val detail: String) : WatchHistoryResult
}

/**
 * Whether a video is ready to be reported, and if not, whose fault it is.
 *
 * The distinction exists because it decides whether to give up on one row or on the whole pass.
 */
public sealed interface SessionResult {
    public data object Opened : SessionResult

    /** YouTube answered, and its answer for this video had no tracking in it. Skip this row. */
    public data object NotTrackable : SessionResult

    /** Nothing could be asked — signed out, no timestamp, the request failed. Probably shared. */
    public data class Unavailable(val reason: String) : SessionResult
}
