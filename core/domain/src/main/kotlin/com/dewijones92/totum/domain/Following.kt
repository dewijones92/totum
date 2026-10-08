package com.dewijones92.totum.domain

public sealed interface Following {
    public data class Subscribed(val source: MediaSource, val byName: Boolean = false) : Following

    public data class NotSubscribed(val source: MediaSource) : Following

    public data class NotSubscribedByName(val item: MediaItem) : Following

    public data class Unknown(val because: UnknownBecause) : Following
}

public enum class UnknownBecause(public val phrase: String) {
    NO_SOURCE("the row names no channel or feed, not even by name"),
    SIGNED_OUT("not signed in to YouTube"),
    NOT_LOADED("subscriptions not loaded yet"),
    HANDLE_ONLY("the channel is named by handle only, with no channel id"),
}

public class FollowedSources(
    channels: List<MediaSource.VideoChannel>?,
    feeds: List<MediaSource.PodcastFeed>?,
    private val signedIn: Boolean,
) {
    private val channelsById: Map<String, MediaSource.VideoChannel>? =
        channels?.mapNotNull { channel -> channel.youTubeChannelId?.let { it to channel } }?.toMap()
    private val channelsBySourceId: Map<SourceId, MediaSource.VideoChannel>? = channels?.associateBy { it.id }
    private val channelsByName: Map<String, MediaSource.VideoChannel>? =
        channels?.reversed()?.associateBy { it.title.nameKey() }
    private val feedsById: Map<SourceId, MediaSource.PodcastFeed>? = feeds?.associateBy { it.id }

    public val channelCount: Int? = channels?.size
    public val feedCount: Int? = feeds?.size

    public fun of(item: MediaItem, pillar: MediaKind): Following = when (pillar) {
        MediaKind.VIDEO -> channelOf(item)
        MediaKind.PODCAST -> feedOf(item)
    }

    private fun channelOf(item: MediaItem): Following {
        val stated = item.statedChannel()
        val name = item.author?.nameKey()?.takeIf { it.isNotEmpty() }
        if (stated == null && name == null) return Following.Unknown(UnknownBecause.NO_SOURCE)
        if (!signedIn) return Following.Unknown(UnknownBecause.SIGNED_OUT)
        val byId = channelsById ?: return Following.Unknown(UnknownBecause.NOT_LOADED)
        val ucId = stated?.youTubeChannelId
        val found = ucId?.let(byId::get) ?: stated?.let { channelsBySourceId?.get(it.id) }
        val byName = name?.let { channelsByName?.get(it) }
        return when {
            found != null -> Following.Subscribed(found)
            ucId != null -> Following.NotSubscribed(stated)
            byName != null -> Following.Subscribed(byName, byName = true)
            stated != null -> Following.Unknown(UnknownBecause.HANDLE_ONLY)
            else -> Following.NotSubscribedByName(item)
        }
    }

    private fun feedOf(item: MediaItem): Following {
        val stated = item.statedFeed() ?: return Following.Unknown(UnknownBecause.NO_SOURCE)
        val feeds = feedsById ?: return Following.Unknown(UnknownBecause.NOT_LOADED)
        return feeds[stated.id]?.let { Following.Subscribed(it) } ?: Following.NotSubscribed(stated)
    }

    public companion object {
        public val NOTHING_KNOWN: FollowedSources = FollowedSources(channels = null, feeds = null, signedIn = false)
    }
}

private fun String.nameKey(): String = trim().lowercase()

public fun MediaItem.statedChannel(): MediaSource.VideoChannel? = sourceUrl?.let { url ->
    MediaSource.VideoChannel(
        id = SourceId(url.value),
        title = author.orEmpty().ifBlank { url.value },
        channelUrl = url,
    )
}

public fun MediaItem.statedFeed(): MediaSource.PodcastFeed? =
    MediaSource.PodcastFeed.feedUrlOf(sourceId)?.let { feedUrl ->
        MediaSource.PodcastFeed(
            id = sourceId,
            title = author.orEmpty().ifBlank { feedUrl.value },
            feedUrl = feedUrl,
            publisher = publisher,
        )
    }
