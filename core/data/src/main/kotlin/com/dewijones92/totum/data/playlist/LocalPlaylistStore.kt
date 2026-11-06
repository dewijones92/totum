package com.dewijones92.totum.data.playlist

import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import kotlinx.coroutines.flow.Flow

/**
 * Stores user-curated local playlists (both pillars). One seam; the Room-backed
 * implementation denormalizes each item so a playlist survives offline and stream
 * expiry.
 */
public interface LocalPlaylistStore {

    /** All playlists, most-recently-created first, with their item counts. */
    public fun observePlaylists(): Flow<List<LocalPlaylist>>

    /** The items of one playlist, in order. */
    public fun observeItems(id: PlaylistId): Flow<List<PlayableItem>>

    /** Creates an empty playlist, returning its id. */
    public suspend fun create(name: String): PlaylistId

    public suspend fun ensure(id: PlaylistId, name: String)

    public suspend fun rename(id: PlaylistId, name: String)

    public suspend fun delete(id: PlaylistId): RemovedPlaylist?

    public suspend fun restore(removed: RemovedPlaylist)

    /** Appends [item] to the end of the playlist (idempotent per item id). */
    public suspend fun addItem(id: PlaylistId, item: PlayableItem)

    /** Puts [item] at index [at] (clamped to the list), pushing what is there and after it down; no-op if present. */
    public suspend fun insertItem(id: PlaylistId, item: PlayableItem, at: Int)

    public suspend fun removeItem(id: PlaylistId, itemId: com.dewijones92.totum.domain.MediaItemId)
}

public data class RemovedPlaylist(
    public val id: PlaylistId,
    public val name: String,
    public val createdAtEpochMs: Long,
    public val items: List<PlayableItem>,
)
