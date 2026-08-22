package com.dewijones92.totum.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A subscribed source — a podcast feed or a video channel, distinguished by
 * [sourceType] ("podcast" | "channel"). `url` holds the feed URL or channel
 * URL; `websiteUrl` is podcast-only. [origin] records how the row got here —
 * "manual" (added by URL) or "youtube_import" (pulled from the signed-in
 * account); only imported rows are pruned when they leave the account's subs,
 * so a manually-added channel is never removed by a sync.
 */
@Entity(tableName = "podcast_feeds")
public data class FeedEntity(
    @PrimaryKey val id: String,
    val sourceType: String,
    val title: String,
    val feedUrl: String,
    val websiteUrl: String?,
    val subscribedAtEpochMs: Long,
    val origin: String = "manual",
)

/**
 * A download record, keyed by media item id. Status is a small string enum;
 * localPath is set only when status == "downloaded".
 *
 * Denormalized on the same [PlaylistItemColumns] contract as queue entries, playlist
 * items and play history, so one mapper rebuilds all four. Without those columns a
 * download was an id and nothing else, and listing what was offline meant joining
 * against one pillar's catalogue — which is why a downloaded video was invisible.
 */
@Entity(tableName = "downloads")
public data class DownloadEntity(
    @PrimaryKey override val itemId: String,
    val status: String,
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val localPath: String?,
    val failureReason: String?,
    /**
     * Whether the local file is audio only (what the queue's automatic downloads
     * take). It stops a later request for the full video being mistaken for
     * "already downloaded", and tells the Library which glyph the row wears.
     */
    val audioOnly: Boolean = false,
    override val title: String,
    override val author: String?,
    override val thumbnailUrl: String?,
    override val sourceId: String,
    override val contentKind: String,
    override val playbackType: String,
    override val handle: String?,
    override val mediaUrl: String?,
    override val viewsText: String? = null,
    override val publishedText: String? = null,
    override val publishedAtEpochMs: Long? = null,
    override val durationMs: Long? = null,
    override val sourceUrl: String? = null,
    override val membersOnly: Boolean = false,
) : PlaylistItemColumns

/**
 * Play state for an item, keyed by media item id. One row per item that has been
 * started or finished.
 *
 * A finished item keeps its row with [completedAtEpochMs] set, rather than being
 * deleted: without it, "played" and "never started" were the same absence, and no
 * list could tell them apart. Resume still starts such an item from the beginning.
 */
@Entity(tableName = "playback_progress")
public data class PlaybackProgressEntity(
    @PrimaryKey val mediaItemId: String,
    val positionMs: Long,
    val durationMs: Long?,
    val updatedAtEpochMs: Long,
    val completedAtEpochMs: Long? = null,
)

@Entity(
    tableName = "podcast_episodes",
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("feedId")],
)
public data class EpisodeEntity(
    @PrimaryKey val id: String,
    val feedId: String,
    val title: String,
    val author: String?,
    val publishedAtEpochMs: Long?,
    val durationSeconds: Long?,
    val description: String?,
    val thumbnailUrl: String?,
    val mediaUrl: String?,
    /** Chapters as a compact JSON array `[{"s":startMs,"t":title}]`; null/absent if none. */
    val chapters: String? = null,
)
