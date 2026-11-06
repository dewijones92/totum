package com.dewijones92.totum.database

import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.data.playlist.RemovedPlaylist
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.PlaylistId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** [LocalPlaylistStore] backed by Room; the one place playlist entities meet domain types. */
public class RoomLocalPlaylistStore(private val dao: LocalPlaylistDao) : LocalPlaylistStore {

    override fun observePlaylists(): Flow<List<LocalPlaylist>> =
        dao.observePlaylists().map { rows -> rows.map { LocalPlaylist(PlaylistId(it.id), it.name, it.itemCount) } }

    override fun observeItems(id: PlaylistId): Flow<List<PlayableItem>> =
        dao.observeItems(id.value).map { list -> list.mapNotNull(::playlistItemFrom) }

    override suspend fun create(name: String): PlaylistId {
        val id = UUID.randomUUID().toString()
        dao.upsertPlaylist(LocalPlaylistEntity(id, name, System.currentTimeMillis()))
        return PlaylistId(id)
    }

    override suspend fun ensure(id: PlaylistId, name: String): Unit =
        dao.insertPlaylistIfAbsent(LocalPlaylistEntity(id.value, name, System.currentTimeMillis()))

    override suspend fun rename(id: PlaylistId, name: String): Unit = dao.rename(id.value, name)

    override suspend fun delete(id: PlaylistId): RemovedPlaylist? {
        val playlist = dao.playlist(id.value) ?: return null
        val items = dao.items(id.value).mapNotNull(::playlistItemFrom)
        dao.deletePlaylist(id.value)
        return RemovedPlaylist(id, playlist.name, playlist.createdAtEpochMs, items)
    }

    override suspend fun restore(removed: RemovedPlaylist) {
        if (dao.playlist(removed.id.value) != null) return
        dao.insertPlaylistIfAbsent(LocalPlaylistEntity(removed.id.value, removed.name, removed.createdAtEpochMs))
        removed.items.forEachIndexed { index, item ->
            dao.insertItem(item.toEntity(removed.id.value, index.toLong()))
        }
    }

    override suspend fun addItem(id: PlaylistId, item: PlayableItem) {
        dao.insertItem(item.toEntity(id.value, dao.nextPosition(id.value)))
    }

    override suspend fun insertItem(id: PlaylistId, item: PlayableItem, at: Int) {
        if (dao.countOf(id.value, item.item.id.value) > 0) return
        val positions = dao.positions(id.value)
        val slot = positions.getOrNull(at.coerceAtLeast(0)) ?: dao.nextPosition(id.value)
        dao.shiftFrom(id.value, slot)
        dao.insertItem(item.toEntity(id.value, slot))
    }

    override suspend fun removeItem(id: PlaylistId, itemId: MediaItemId): Unit =
        dao.deleteItem(id.value, itemId.value)

    private fun PlayableItem.toEntity(playlistId: String, position: Long): LocalPlaylistItemEntity {
        val (type, handle) = handle.typeAndHandle()
        return LocalPlaylistItemEntity(
            playlistId = playlistId,
            itemId = item.id.value,
            position = position,
            title = item.title,
            author = item.author,
            publisher = item.publisher,
            thumbnailUrl = item.thumbnailUrl?.value,
            sourceId = item.sourceId.value,
            contentKind = item.contentKind.name,
            // The listing's own facts, or they are lost the moment the row is written -- see
            // PlaylistItemColumns.
            viewsText = item.viewsText,
            publishedText = item.publishedText,
            publishedAtEpochMs = item.publishedAt?.toEpochMilli(),
            durationMs = item.duration?.inWholeMilliseconds,
            sourceUrl = item.sourceUrl?.value,
            membersOnly = item.membersOnly,
            albumId = item.album?.id,
            albumTitle = item.album?.title,
            playbackType = type,
            handle = handle,
            mediaUrl = item.mediaUrl?.storedValue(),
        )
    }
}
