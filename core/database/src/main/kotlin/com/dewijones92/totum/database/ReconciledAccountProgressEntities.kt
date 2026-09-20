package com.dewijones92.totum.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The account position already acted on for one item. See `ReconciledAccountProgress`. */
@Entity(tableName = "account_progress_reconciled")
public data class ReconciledAccountProgressEntity(
    @PrimaryKey val mediaItemId: String,
    val positionMs: Long,
    val reconciledAtEpochMs: Long,
)

@Dao
public interface ReconciledAccountProgressDao {

    @Upsert
    public suspend fun upsert(entity: ReconciledAccountProgressEntity)

    @Query("SELECT * FROM account_progress_reconciled WHERE mediaItemId = :id")
    public suspend fun get(id: String): ReconciledAccountProgressEntity?

    @Query("SELECT * FROM account_progress_reconciled")
    public fun observeAll(): Flow<List<ReconciledAccountProgressEntity>>

    @Query("DELETE FROM account_progress_reconciled")
    public suspend fun deleteAll()
}
