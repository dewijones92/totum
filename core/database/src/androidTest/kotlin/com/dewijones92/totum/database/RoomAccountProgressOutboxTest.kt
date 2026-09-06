package com.dewijones92.totum.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PendingAccountProgress
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** The outbox's one subtle promise: a send must not delete a record written while it was in flight. */
class RoomAccountProgressOutboxTest {

    private lateinit var database: TotumDatabase
    private lateinit var outbox: RoomAccountProgressOutbox
    private val id = MediaItemId("vid-1")

    @Before
    fun create() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        outbox = RoomAccountProgressOutbox(database.accountProgressOutboxDao())
    }

    @After
    fun close() = database.close()

    @Test
    fun keepsTheLatestRecordPerItem() = runTest {
        outbox.record(PendingAccountProgress(id, 10_000, 600_000, finished = false, recordedAtEpochMs = 1))
        outbox.record(PendingAccountProgress(id, 20_000, 600_000, finished = false, recordedAtEpochMs = 2))

        assertEquals(listOf(20_000L), outbox.pending().map { it.positionMs })
        assertEquals(1, outbox.observePendingCount().first())
    }

    @Test
    fun aSendRemovesTheRecordItSent() = runTest {
        outbox.record(PendingAccountProgress(id, 10_000, 600_000, finished = true, recordedAtEpochMs = 1))
        outbox.sent(id, recordedAtEpochMs = 1)

        assertEquals(0, outbox.observePendingCount().first())
    }

    /** Playback carried on during the send: the newer position must survive the older send completing. */
    @Test
    fun aSendDoesNotRemoveARecordWrittenWhileItWasInFlight() = runTest {
        outbox.record(PendingAccountProgress(id, 10_000, 600_000, finished = false, recordedAtEpochMs = 1))
        outbox.record(PendingAccountProgress(id, 30_000, 600_000, finished = false, recordedAtEpochMs = 2))
        outbox.sent(id, recordedAtEpochMs = 1)

        assertEquals(listOf(30_000L), outbox.pending().map { it.positionMs })
    }
}
