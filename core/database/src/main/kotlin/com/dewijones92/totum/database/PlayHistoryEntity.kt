package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A recently-played item (denormalized, same columns as a playlist item + when it was played). */
@Entity(tableName = "play_history")
public data class PlayHistoryEntity(
    @PrimaryKey override val itemId: String,
    public val lastPlayedAtEpochMs: Long,
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

@Dao
public interface PlayHistoryDao {

    @Query("SELECT * FROM play_history ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit")
    public fun observe(limit: Int): Flow<List<PlayHistoryEntity>>

    @Upsert
    public suspend fun upsert(entity: PlayHistoryEntity)

    /** Drops all but the [limit] most-recent, so history stays bounded. */
    @Query(
        "DELETE FROM play_history WHERE itemId NOT IN " +
            "(SELECT itemId FROM play_history ORDER BY lastPlayedAtEpochMs DESC LIMIT :limit)",
    )
    public suspend fun trim(limit: Int)

    @Query("DELETE FROM play_history")
    public suspend fun clear()
}
