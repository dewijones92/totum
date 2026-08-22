package com.dewijones92.totum.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** A user-created local playlist (both pillars). */
@Entity(tableName = "local_playlists")
public data class LocalPlaylistEntity(
    @PrimaryKey public val id: String,
    public val name: String,
    public val createdAtEpochMs: Long,
)

/**
 * One item in a local playlist. Denormalized (the display fields + a play handle)
 * so a playlist survives offline and stream-URL expiry — a video keeps its stable
 * watch URL, a podcast its enclosure/download.
 */
@Entity(
    tableName = "local_playlist_items",
    primaryKeys = ["playlistId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
public data class LocalPlaylistItemEntity(
    public val playlistId: String,
    override val itemId: String,
    public val position: Long,
    override val title: String,
    override val author: String?,
    override val thumbnailUrl: String?,
    override val sourceId: String,
    override val contentKind: String,
    /** VIDEO | LOCAL_VIDEO | PODCAST — how to play (mirrors the queue shapes). */
    override val playbackType: String,
    /** Video watch URL, or a local file path; null for a streamed podcast. */
    override val handle: String?,
    /** A podcast's enclosure URL (its playable media); null for videos. */
    override val mediaUrl: String?,
    override val viewsText: String? = null,
    override val publishedText: String? = null,
    override val publishedAtEpochMs: Long? = null,
    override val durationMs: Long? = null,
    override val sourceUrl: String? = null,
    override val membersOnly: Boolean = false,
) : PlaylistItemColumns

/** Playlist + its item count, for the list screen. */
public data class PlaylistCountRow(
    public val id: String,
    public val name: String,
    public val itemCount: Int,
)
