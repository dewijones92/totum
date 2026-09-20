package com.dewijones92.totum.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The account position already acted on, which is what tells a NEW remote figure from an echo of
 * the last decision. It has to survive the process, or a cold start hands the stale figure its
 * veto straight back (report 0.1.496).
 */
class RoomReconciledAccountProgressTest {

    private lateinit var database: TotumDatabase
    private lateinit var store: RoomReconciledAccountProgress
    private val id = MediaItemId("vceHVwxOnhA")

    @Before
    fun create() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        store = RoomReconciledAccountProgress(database.reconciledAccountProgressDao()) { 0L }
    }

    @After
    fun close() = database.close()

    @Test
    fun remembersWhatWasActedOn() = runTest {
        store.reconcile(id, 77_700)

        assertEquals(77_700L, store.reconciledMs(id))
        assertEquals(mapOf(id to 77_700L), store.observeReconciled().first())
    }

    @Test
    fun keepsOnlyTheLatestFigurePerItem() = runTest {
        store.reconcile(id, 77_700)
        store.reconcile(id, 2_400_000)

        assertEquals(2_400_000L, store.reconciledMs(id))
    }

    @Test
    fun anItemNeverReconciledKnowsNothing() = runTest {
        assertNull(store.reconciledMs(MediaItemId("never-seen")))
    }
}
