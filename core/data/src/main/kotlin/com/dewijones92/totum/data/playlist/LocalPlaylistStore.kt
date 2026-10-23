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

    public suspend fun delete(id: PlaylistId)

    /** Appends [item] to the end of the playlist (idempotent per item id). */
    public suspend fun addItem(id: PlaylistId, item: PlayableItem)

    public suspend fun removeItem(id: PlaylistId, itemId: com.dewijones92.totum.domain.MediaItemId)
}
