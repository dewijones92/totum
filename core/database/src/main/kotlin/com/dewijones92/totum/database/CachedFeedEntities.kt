package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * The last-known contents of one video feed, so the app opens with something on screen.
 *
 * Podcast episodes have been persisted since the beginning; YouTube feed videos never were.
 * So every launch showed an empty Videos tab until the network answered — measured
 * 2026-07-31: `[place] videos entered … videos=0`, populated 1.2 seconds later. PipePipe shows
 * yesterday's feed instantly and refreshes behind it, which is the whole difference.
 *
 * **Deliberately NOT on [PlaylistItemColumns].** That contract exists to rebuild a
 * `PlayableItem` — it carries a playback handle, which a feed row does not need (one is
 * derived at play time), and four tables share it.
 */
@Entity(
    tableName = "cached_feed_items",
    primaryKeys = ["feedKey", "itemId"],
    indices = [Index("feedKey")],
)
public data class CachedFeedItemEntity(
    /** Which feed this row belongs to — an [AccountFeed] name, or `group:<id>`. */
    public val feedKey: String,
    public val itemId: String,
    /** Feed order, which is meaningful (recency, or YouTube's own ranking) and not derivable. */
    public val position: Int,
    public val cachedAtEpochMs: Long,
    public val sourceId: String,
    public val title: String,
    public val author: String?,
    public val thumbnailUrl: String?,
    public val mediaUrl: String?,
    public val publishedText: String?,
    public val viewsText: String?,
    public val durationSeconds: Long?,
    public val membersOnly: Boolean,
    public val contentKind: String,
    /** The uploader's channel page, so a cached row's "go to channel" stays instant too. */
    public val sourceUrl: String?,
)

@Dao
public interface CachedFeedDao {

    @Query("SELECT * FROM cached_feed_items WHERE feedKey = :feedKey ORDER BY position ASC")
    public suspend fun itemsFor(feedKey: String): List<CachedFeedItemEntity>

    /**
     * Replaces a feed's cached contents wholesale, in one transaction.
     *
     * Replace rather than upsert: a feed is a snapshot, and merging would resurrect videos
     * YouTube has since dropped from it — a watch-later item removed elsewhere would reappear
     * and never leave. The transaction matters because the delete and the insert must not be
     * observable apart, or a launch mid-write would find the tab empty.
     */
    @Transaction
    public suspend fun replace(feedKey: String, items: List<CachedFeedItemEntity>) {
        clear(feedKey)
        insertAll(items)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    public suspend fun insertAll(items: List<CachedFeedItemEntity>)

    @Query("DELETE FROM cached_feed_items WHERE feedKey = :feedKey")
    public suspend fun clear(feedKey: String)

    @Query("DELETE FROM cached_feed_items")
    public suspend fun clearAll()
}
