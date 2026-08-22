package com.dewijones92.totum.database

import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.QueueStore

/** [QueueStore] backed by Room, reusing the shared playable-item column mapping. */
public class RoomQueueStore(private val dao: QueueDao) : QueueStore {

    override suspend fun load(): QueueSnapshot {
        val rows = dao.all()
        // A row that can't be rebuilt (an unusable handle) is dropped, so the cursor
        // is derived from what survived rather than from the raw row positions.
        val kept = rows.mapNotNull { row ->
            val item = playlistItemFrom(row) ?: return@mapNotNull null
            val group = row.groupId?.let { QueueGroup(it, row.groupTitle.orEmpty()) }
            QueueEntry(item, group) to row.isCurrent
        }
        return QueueSnapshot(
            entries = kept.map { it.first },
            currentIndex = kept.indexOfFirst { it.second },
        )
    }

    override suspend fun save(snapshot: QueueSnapshot) {
        dao.replaceAll(
            snapshot.entries.mapIndexed { index, entry ->
                val (type, handle) = entry.item.handle.typeAndHandle()
                val media = entry.item.item
                QueueEntity(
                    position = index.toLong(),
                    groupId = entry.group?.id,
                    groupTitle = entry.group?.title,
                    isCurrent = index == snapshot.currentIndex,
                    itemId = media.id.value,
                    title = media.title,
                    author = media.author,
                    thumbnailUrl = media.thumbnailUrl?.value,
                    sourceId = media.sourceId.value,
                    contentKind = media.contentKind.name,
                    // The listing's own facts, or they are lost the moment the row is written -- see
                    // PlaylistItemColumns.
                    viewsText = media.viewsText,
                    publishedText = media.publishedText,
                    publishedAtEpochMs = media.publishedAt?.toEpochMilli(),
                    durationMs = media.duration?.inWholeMilliseconds,
                    sourceUrl = media.sourceUrl?.value,
                    membersOnly = media.membersOnly,
                    playbackType = type,
                    handle = handle,
                    mediaUrl = media.mediaUrl?.value,
                )
            },
        )
    }
}
