package com.dewijones92.totum.data.podcast

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import kotlinx.coroutines.flow.Flow

/** The app's single source of truth for podcasts. */
public interface PodcastRepository {

    /** All subscriptions, stable order (newest first). */
    public fun observeSubscriptions(): Flow<List<Subscription>>

    /** Episodes across all subscribed feeds, newest first. */
    public fun observeEpisodes(): Flow<List<MediaItem>>

    /** Fetches, parses, and stores [feedUrl]. Idempotent per URL. */
    public suspend fun subscribe(feedUrl: HttpUrl): SubscribeResult

    public suspend fun preview(feedUrl: HttpUrl): PreviewResult

    /** Removes the subscription and its episodes. */
    public suspend fun unsubscribe(id: SourceId)

    /**
     * Re-fetches every subscribed feed and upserts its episodes (pull-to-refresh). Feeds that
     * fail to fetch or parse are skipped, leaving their stored episodes intact.
     *
     * Returns what happened, rather than nothing. Skipping a broken feed is the right
     * behaviour — a 404 must not wipe episodes already on the device — but it used to be
     * completely silent: three bare `return`s, no log, no counter, nothing on screen. A feed
     * that moved or started serving malformed XML simply stopped updating, and "why has this
     * podcast not updated in three weeks?" could not be answered from a diagnostics report at
     * all. The video pillar learned this lesson repeatedly; the podcast one never got it.
     */
    public suspend fun refresh(): RefreshReport
}

/**
 * What one refresh did, per feed.
 *
 * A count of failures is not enough to act on — the useful question is always *which* feed and
 * *why*, since the fix differs: a moved feed needs re-subscribing, a malformed one is the
 * publisher's problem, and everything failing at once just means no network.
 */
public data class RefreshReport(
    public val updated: List<SourceId> = emptyList(),
    public val failures: List<FeedRefreshFailure> = emptyList(),
) {
    public val total: Int get() = updated.size + failures.size

    /** True when every feed failed, which nearly always means the network rather than the feeds. */
    public val allFailed: Boolean get() = failures.isNotEmpty() && updated.isEmpty()
}

/** Why one feed did not update. Sealed so a new cause cannot be added without a message for it. */
public sealed interface FeedRefreshFailure {
    public val id: SourceId
    public val title: String

    /** The feed could not be fetched: offline, DNS, 404, TLS — [detail] carries which. */
    public data class Unreachable(
        override val id: SourceId,
        override val title: String,
        public val detail: String,
    ) : FeedRefreshFailure

    /** Fetched, but the XML would not parse. The publisher's problem, and not retryable. */
    public data class Unparseable(
        override val id: SourceId,
        override val title: String,
        public val detail: String,
    ) : FeedRefreshFailure

    /** A subscription that is not a podcast feed at all — a video channel reached this path. */
    public data class NotAFeed(
        override val id: SourceId,
        override val title: String,
    ) : FeedRefreshFailure
}

public sealed interface PreviewResult {
    public data class Loaded(val source: MediaSource.PodcastFeed, val episodes: List<MediaItem>) : PreviewResult
    public data class Failed(val detail: String) : PreviewResult
}

/** Outcome of a subscribe attempt; expected failures are values. */
public sealed interface SubscribeResult {
    public data class Subscribed(val source: MediaSource.PodcastFeed) : SubscribeResult
    public data class AlreadySubscribed(val id: SourceId) : SubscribeResult

    public sealed interface Failure : SubscribeResult {
        public data class Network(val detail: String) : Failure
        public data class InvalidFeed(val detail: String) : Failure
    }
}

/**
 * The one-line reason a feed did not update, for a log or a screen.
 *
 * Here rather than at each call site so the wording is the same wherever it appears — a user
 * reading "couldn't be reached" in the app and a report saying the same thing are describing one
 * event, and two phrasings of it would waste time proving that.
 */
public fun FeedRefreshFailure.describe(): String = when (this) {
    is FeedRefreshFailure.Unreachable -> "could not be reached ($detail)"
    is FeedRefreshFailure.Unparseable -> "was fetched but would not parse ($detail)"
    is FeedRefreshFailure.NotAFeed -> "is not a podcast feed"
}
