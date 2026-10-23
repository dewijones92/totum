package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
public interface LocalPlaylistDao {

    @Query(
        "SELECT p.id AS id, p.name AS name, " +
            "(SELECT COUNT(*) FROM local_playlist_items i WHERE i.playlistId = p.id) AS itemCount " +
            "FROM local_playlists p ORDER BY p.createdAtEpochMs DESC",
    )
    public fun observePlaylists(): Flow<List<PlaylistCountRow>>

    @Query("SELECT * FROM local_playlist_items WHERE playlistId = :id ORDER BY position")
    public fun observeItems(id: String): Flow<List<LocalPlaylistItemEntity>>

    @Upsert
    public suspend fun upsertPlaylist(playlist: LocalPlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertPlaylistIfAbsent(playlist: LocalPlaylistEntity)

    @Query("UPDATE local_playlists SET name = :name WHERE id = :id")
    public suspend fun rename(id: String, name: String)

    @Query("DELETE FROM local_playlists WHERE id = :id")
    public suspend fun deletePlaylist(id: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM local_playlist_items WHERE playlistId = :id")
    public suspend fun nextPosition(id: String): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    public suspend fun insertItem(item: LocalPlaylistItemEntity)

    @Query("DELETE FROM local_playlist_items WHERE playlistId = :playlistId AND itemId = :itemId")
    public suspend fun deleteItem(playlistId: String, itemId: String)
}
