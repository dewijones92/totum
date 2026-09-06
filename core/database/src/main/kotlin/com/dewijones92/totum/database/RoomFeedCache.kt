package com.dewijones92.totum.database

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.domain.SourceId
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * [FeedCache] over Room. The one place cached-feed rows meet domain types.
 *
 * Bounded per feed, because a feed pages: without a cap, scrolling a subscriptions feed for a
 * while would persist hundreds of rows that nobody will ever look at again on the next launch —
 * the point is to fill the first screen, not to mirror the feed.
 */
public class RoomFeedCache(
    private val dao: CachedFeedDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxItemsPerFeed: Int = MAX_ITEMS_PER_FEED,
) : FeedCache {

    override suspend fun items(feedKey: String): List<MediaItem> =
        dao.itemsFor(feedKey).map { it.toMediaItem() }

    override suspend fun save(feedKey: String, items: List<MediaItem>) {
        val cachedAt = now()
        dao.replace(
            feedKey,
            items.take(maxItemsPerFeed).mapIndexed { position, item ->
                item.toEntity(feedKey, position, cachedAt)
            },
        )
    }

    private fun CachedFeedItemEntity.toMediaItem() = MediaItem(
        id = MediaItemId(itemId),
        sourceId = SourceId(sourceId),
        title = title,
        // The feed gives wording ("2 days ago"), never an instant; anchored to when it was cached so
        // the row keeps ageing from the right moment (Dewi, 2026-09-06).
        publishedAt = publishedText?.let { PublishedAge.parse(it, Instant.ofEpochMilli(cachedAtEpochMs)) },
        publishedText = publishedText,
        duration = durationSeconds?.seconds,
        author = author,
        thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
        mediaUrl = mediaUrl?.let(HttpUrl::parse),
        viewsText = viewsText,
        membersOnly = membersOnly,
        contentKind = runCatching { MediaContentKind.valueOf(contentKind) }
            .getOrDefault(MediaContentKind.STANDARD),
        sourceUrl = sourceUrl?.let(HttpUrl::parse),
    )

    private fun MediaItem.toEntity(feedKey: String, position: Int, cachedAt: Long) = CachedFeedItemEntity(
        feedKey = feedKey,
        itemId = id.value,
        position = position,
        cachedAtEpochMs = cachedAt,
        sourceId = sourceId.value,
        title = title,
        author = author,
        thumbnailUrl = thumbnailUrl?.value,
        mediaUrl = mediaUrl?.value,
        publishedText = publishedText,
        viewsText = viewsText,
        durationSeconds = duration?.inWholeSeconds,
        membersOnly = membersOnly,
        contentKind = contentKind.name,
        sourceUrl = sourceUrl?.value,
    )

    private companion object {
        /** About two screens' worth — enough that the tab is never empty, and no more. */
        const val MAX_ITEMS_PER_FEED = 60
    }
}
