package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.fillingSilenceFrom
import kotlinx.coroutines.flow.Flow

@Dao
public interface DownloadDao {

    @Query("SELECT * FROM downloads")
    public fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE itemId = :id")
    public suspend fun get(id: String): DownloadEntity?

    @Upsert
    public suspend fun upsert(entity: DownloadEntity)

    @Transaction
    public suspend fun learnFacts(resolved: MediaItem) {
        val row = get(resolved.id.value) ?: return
        upsert(row.learningFactsFrom(resolved))
    }

    @Transaction
    public suspend fun putKeepingFacts(entity: DownloadEntity) {
        val held = get(entity.itemId)?.let(::playlistItemFrom)?.item
        upsert(if (held == null) entity else entity.learningFactsFrom(held))
    }

    @Query("DELETE FROM downloads WHERE itemId = :id")
    public suspend fun delete(id: String)
}

private fun DownloadEntity.learningFactsFrom(resolved: MediaItem): DownloadEntity {
    val item = playlistItemFrom(this)?.item ?: return this
    val filled = item.fillingSilenceFrom(resolved)
    return copy(
        title = filled.title,
        author = filled.author,
        publisher = filled.publisher,
        thumbnailUrl = filled.thumbnailUrl?.value,
        viewsText = filled.viewsText,
        publishedText = filled.publishedText,
        publishedAtEpochMs = filled.publishedAt?.toEpochMilli(),
        durationMs = filled.duration?.inWholeMilliseconds,
    )
}
