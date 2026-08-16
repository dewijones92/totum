package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl

/** Stable identity of a [MediaSource]; never blank. */
@JvmInline
public value class SourceId(public val value: String) {
    init {
        require(value.isNotBlank()) { "SourceId must not be blank" }
    }
}

/**
 * Something the user can subscribe to. The two pillars of the app are the
 * two variants: video channels and podcast feeds. Everything downstream
 * (subscriptions, queue, downloads, history) treats them uniformly.
 */
public sealed interface MediaSource {
    public val id: SourceId
    public val title: String

    public data class VideoChannel(
        override val id: SourceId,
        override val title: String,
        val channelUrl: HttpUrl,
    ) : MediaSource

    public data class PodcastFeed(
        override val id: SourceId,
        override val title: String,
        val feedUrl: HttpUrl,
        val websiteUrl: HttpUrl? = null,
    ) : MediaSource
}

/**
 * The channel's stable YouTube identity — its `UC…` id — or null when the URL does not carry one.
 *
 * A channel is reachable by several URLs that all mean the same channel: `/channel/UC…`,
 * `/@handle`, and the legacy `/c/Name`. Comparing the URLs as strings therefore answers "is this
 * the same LINK", not "is this the same CHANNEL", and the two disagree constantly.
 *
 * That disagreement was a real bug (Dewi, 2026-07-29): the app offered to subscribe to channels he
 * was already subscribed to. Subscriptions arrive from the account keyed by their canonical
 * `/channel/UC…` URL, but a channel opened from a video row or a search hit carries whatever form
 * that source used — so the equality check failed and the button said "Subscribe".
 *
 * It also replaces the same `substringAfterLast` copy-pasted into three files, which is how a rule
 * like this drifts in the first place.
 */
public val MediaSource.VideoChannel.youTubeChannelId: String?
    get() = channelUrl.youTubeChannelId

/**
 * The same rule, on the URL itself — for the callers that hold one without a [MediaSource] around
 * it, such as a feed item's `sourceUrl`.
 *
 * Here rather than at those call sites, because the whole point of this property is that the rule
 * lives in one place: it replaced the same `substringAfterLast` copy-pasted into three files, and
 * a fourth copy taking a URL would be the same drift starting again.
 */
public val HttpUrl.youTubeChannelId: String?
    get() = value
        .substringAfterLast("/channel/", "")
        // Only the id segment: the three copied versions of this took everything after
        // "/channel/", so a perfectly ordinary "/channel/UC…/videos" or "?view=0" yielded an id
        // with the rest of the path glued on, and then matched nothing.
        .substringBefore('/')
        .substringBefore('?')
        .ifBlank { null }

/**
 * Whether these two references point at the same channel.
 *
 * Prefers the `UC…` id, and falls back to URL equality only when neither side has one — at which
 * point the URL is genuinely all the identity there is.
 */
public fun MediaSource.VideoChannel.isSameChannelAs(other: MediaSource.VideoChannel): Boolean {
    val mine = youTubeChannelId
    val theirs = other.youTubeChannelId
    return if (mine != null && theirs != null) mine == theirs else id == other.id
}

/**
 * Whether this list of channels contains [source], preferring [resolvedId] over anything the
 * source's own URL can offer.
 *
 * Two identities are in play and only one is reliable. An account's subscriptions are keyed by
 * canonical `/channel/UC…` URLs, but the channel in front of the user may have arrived as a
 * handle — `/@name` — whose URL yields no id at all. Given the id resolved by actually loading
 * the channel, the comparison is exact. Without one it falls back to [isSameChannelAs], which
 * still beats string equality on URLs but cannot match a handle against a canonical URL.
 */
public fun List<MediaSource.VideoChannel>.containsChannel(
    source: MediaSource.VideoChannel,
    resolvedId: String? = null,
): Boolean = when (resolvedId) {
    null -> any { it.isSameChannelAs(source) }
    else -> any { it.youTubeChannelId == resolvedId || it.isSameChannelAs(source) }
}
