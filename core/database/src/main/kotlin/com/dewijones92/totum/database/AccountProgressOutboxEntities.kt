package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** One item's latest progress the account has not been told about. See `AccountProgressOutbox`. */
@Entity(tableName = "account_progress_outbox")
public data class AccountProgressOutboxEntity(
    @PrimaryKey val mediaItemId: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val recordedAtEpochMs: Long,
    val attempts: Int = 0,
)

@Dao
public interface AccountProgressOutboxDao {

    @Upsert
    public suspend fun upsert(entity: AccountProgressOutboxEntity)

    @Query("SELECT * FROM account_progress_outbox ORDER BY attempts ASC, recordedAtEpochMs ASC")
    public suspend fun pending(): List<AccountProgressOutboxEntity>

    @Query("SELECT * FROM account_progress_outbox WHERE mediaItemId = :id")
    public suspend fun get(id: String): AccountProgressOutboxEntity?

    /** Deletes only the record as it was when the send began, so a newer one written meanwhile survives. */
    @Query("DELETE FROM account_progress_outbox WHERE mediaItemId = :id AND recordedAtEpochMs = :recordedAtEpochMs")
    public suspend fun deleteIfUnchanged(id: String, recordedAtEpochMs: Long)

    /** Same staleness guard: a record rewritten while the send was in flight keeps its own count. */
    @Query(
        "UPDATE account_progress_outbox SET attempts = attempts + 1 " +
            "WHERE mediaItemId = :id AND recordedAtEpochMs = :recordedAtEpochMs",
    )
    public suspend fun countAttemptIfUnchanged(id: String, recordedAtEpochMs: Long)

    @Query("SELECT COUNT(*) FROM account_progress_outbox")
    public fun observeCount(): Flow<Int>

    /** [after] rather than any failure at all: one offline stretch marks dozens of healthy rows. */
    @Query("SELECT * FROM account_progress_outbox WHERE attempts >= :after ORDER BY attempts DESC LIMIT :worst")
    public fun observeStuck(after: Int, worst: Int): Flow<List<AccountProgressOutboxEntity>>
}
