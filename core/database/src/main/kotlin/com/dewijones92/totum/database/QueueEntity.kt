package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

/**
 * One saved up-next entry. Denormalized on the same [PlaylistItemColumns] contract
 * as playlist items and play history, so one mapper rebuilds all three.
 *
 * [groupId]/[groupTitle] tag the run this entry arrived in (a "Play all"); they are
 * display-only — playback reads the table as a flat ordered list.
 */
@Entity(tableName = "queue_items")
public data class QueueEntity(
    @PrimaryKey(autoGenerate = true) public val rowId: Long = 0,
    public val position: Long,
    public val groupId: String?,
    public val groupTitle: String?,
    /** Exactly one row is the playing one, so the cursor survives a restart too. */
    public val isCurrent: Boolean = false,
    override val itemId: String,
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
public interface QueueDao {

    @Query("SELECT * FROM queue_items ORDER BY position")
    public suspend fun all(): List<QueueEntity>

    /**
     * Replaces the whole queue in one transaction. The queue is small and every
     * mutation reorders it, so a wholesale replace is simpler — and atomically
     * correct — versus diffing positions.
     */
    @Transaction
    public suspend fun replaceAll(entries: List<QueueEntity>) {
        deleteAll()
        insertAll(entries)
    }

    @Query("DELETE FROM queue_items")
    public suspend fun deleteAll()

    @Insert
    public suspend fun insertAll(entries: List<QueueEntity>)
}
