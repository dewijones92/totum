package com.dewijones92.totum.database

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.ChapterJson
import com.dewijones92.totum.data.subscription.SubscriptionStore
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Room-backed [SubscriptionStore]. One class serves both pillars: [sourceType]
 * selects whether this instance stores/reads podcast feeds or video channels,
 * using the same tables. The only place entities and domain types meet.
 */
public class RoomSubscriptionStore(
    private val dao: PodcastDao,
    private val sourceType: SourceType,
) : SubscriptionStore {

    public enum class SourceType(internal val key: String) { PODCAST("podcast"), CHANNEL("channel") }

    override fun observeSubscriptions(): Flow<List<Subscription>> =
        dao.observeFeeds(sourceType.key).map { feeds -> feeds.map { it.toSubscription() } }

    override fun observeItems(): Flow<List<MediaItem>> =
        dao.observeEpisodes(sourceType.key).map { items -> items.map { it.toMediaItem() } }

    override suspend fun contains(id: SourceId): Boolean = dao.countFeeds(id.value) > 0

    override suspend fun saveSource(subscription: Subscription, items: List<MediaItem>) {
        dao.upsertFeedWithEpisodes(
            feed = subscription.source.toEntity(subscription.subscribedAt),
            episodes = items.map { it.toEntity() },
        )
    }

    override suspend fun removeSource(id: SourceId) {
        dao.deleteFeed(id.value)
    }

    private fun MediaSource.toEntity(subscribedAt: Instant): FeedEntity = when (this) {
        is MediaSource.PodcastFeed -> FeedEntity(
            id = id.value,
            sourceType = SourceType.PODCAST.key,
            title = title,
            publisher = publisher,
            feedUrl = feedUrl.value,
            websiteUrl = websiteUrl?.value,
            subscribedAtEpochMs = subscribedAt.toEpochMilli(),
            artworkUrl = artworkUrl?.value,
        )
        is MediaSource.VideoChannel -> FeedEntity(
            id = id.value,
            sourceType = SourceType.CHANNEL.key,
            title = title,
            feedUrl = channelUrl.value,
            websiteUrl = null,
            subscribedAtEpochMs = subscribedAt.toEpochMilli(),
            artworkUrl = artworkUrl?.value,
        )
    }

    private fun FeedEntity.toSubscription(): Subscription {
        val source = when (sourceType) {
            SourceType.CHANNEL.key -> MediaSource.VideoChannel(
                id = SourceId(id),
                title = title,
                channelUrl = HttpUrl.of(feedUrl),
                artworkUrl = artworkUrl?.let(HttpUrl::parse),
            )
            else -> MediaSource.PodcastFeed(
                id = SourceId(id),
                title = title,
                feedUrl = HttpUrl.of(feedUrl),
                websiteUrl = websiteUrl?.let(HttpUrl::parse),
                publisher = publisher,
                artworkUrl = artworkUrl?.let(HttpUrl::parse),
            )
        }
        return Subscription(source = source, subscribedAt = Instant.ofEpochMilli(subscribedAtEpochMs))
    }

    private fun EpisodeEntity.toMediaItem() = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId(feedId),
        title = title,
        publishedAt = publishedAtEpochMs?.let(Instant::ofEpochMilli),
        duration = durationSeconds?.seconds,
        author = author,
        publisher = publisher,
        description = description,
        thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
        mediaUrl = mediaUrl?.let(HttpUrl::parse),
        chapters = ChapterJson.decode(chapters),
    )

    private fun MediaItem.toEntity() = EpisodeEntity(
        id = id.value,
        feedId = sourceId.value,
        title = title,
        author = author,
        publisher = publisher,
        publishedAtEpochMs = publishedAt?.toEpochMilli(),
        durationSeconds = duration?.inWholeSeconds,
        description = description,
        thumbnailUrl = thumbnailUrl?.value,
        mediaUrl = mediaUrl?.value,
        chapters = ChapterJson.encode(chapters),
    )
}
