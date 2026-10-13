package com.dewijones92.totum.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DownloadDatesTest {

    private companion object {
        const val NAME = "download-dates-test.db"
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var db: TotumDatabase? = null
    private var clock = Instant.parse("2026-10-01T09:00:00Z")

    private val episode = PlayableItem(
        MediaItem(
            id = MediaItemId("ep-1"),
            sourceId = SourceId("feed-1"),
            title = "Episode one",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @After
    fun cleanUp() {
        db?.close()
        context.deleteDatabase(NAME)
    }

    private fun open(): TotumDatabase =
        Room.databaseBuilder(context, TotumDatabase::class.java, NAME)
            .apply { TotumDatabase.MIGRATIONS.forEach { addMigrations(it) } }
            .allowMainThreadQueries()
            .build()
            .also { db = it }

    private fun TotumDatabase.store() = RoomDownloadStore(downloadDao()) { clock }

    private suspend fun RoomDownloadStore.only() = observeDownloaded().first().single()

    @Test
    fun aFinishedDownloadRecordsWhenItFinished() = runBlocking {
        context.deleteDatabase(NAME)
        val store = open().store()

        store.put(episode, DownloadState.Downloading(10, 100), audioOnly = false)
        clock = Instant.parse("2026-10-02T10:00:00Z")
        store.put(episode, DownloadState.Downloaded("/data/ep.media", audioOnly = false), audioOnly = false)

        assertEquals(Instant.parse("2026-10-02T10:00:00Z"), store.only().downloadedAt)
        assertNull(store.only().openedAt)
    }

    @Test
    fun rewritingAFinishedDownloadKeepsItsDateAndItsOpen() = runBlocking {
        context.deleteDatabase(NAME)
        val store = open().store()
        store.put(episode, DownloadState.Downloaded("/data/ep.media", audioOnly = false), audioOnly = false)
        clock = Instant.parse("2026-10-05T08:00:00Z")
        assertTrue(store.noteOpened(episode.item.id))

        clock = Instant.parse("2026-10-09T08:00:00Z")
        store.put(episode, DownloadState.Downloaded("/data/ep.media", audioOnly = false), audioOnly = false)

        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), store.only().downloadedAt)
        assertEquals(Instant.parse("2026-10-05T08:00:00Z"), store.only().openedAt)
    }

    @Test
    fun downloadingAgainStartsANewDate() = runBlocking {
        context.deleteDatabase(NAME)
        val store = open().store()
        store.put(episode, DownloadState.Downloaded("/data/ep.media", audioOnly = true), audioOnly = true)

        clock = Instant.parse("2026-10-09T08:00:00Z")
        store.put(episode, DownloadState.Downloading(0, null), audioOnly = false)
        store.put(episode, DownloadState.Downloaded("/data/ep-full.media", audioOnly = false), audioOnly = false)

        assertEquals(Instant.parse("2026-10-09T08:00:00Z"), store.only().downloadedAt)
    }

    @Test
    fun openingSomethingNotDownloadedChangesNothing() = runBlocking {
        context.deleteDatabase(NAME)
        val store = open().store()

        assertFalse(store.noteOpened(MediaItemId("not-here")))
        assertTrue(store.observeDownloaded().first().isEmpty())
    }

    @Test
    fun downloadsFromBeforeTheUpgradeAreDatedAtTheUpgrade() = runBlocking {
        context.deleteDatabase(NAME)
        val fresh = open()
        fresh.store().put(episode, DownloadState.Downloaded("/data/ep.media", audioOnly = false), audioOnly = false)
        val raw = fresh.openHelper.writableDatabase
        raw.execSQL("ALTER TABLE downloads DROP COLUMN downloadedAtEpochMs")
        raw.execSQL("ALTER TABLE downloads DROP COLUMN openedAtEpochMs")
        raw.execSQL("PRAGMA user_version = 26")
        fresh.close()
        db = null

        val before = Instant.now().minusSeconds(5)
        val migrated = open().store().only()

        val dated = requireNotNull(migrated.downloadedAt)
        assertTrue("dated $dated, expected about now", dated.isAfter(before))
        assertNull(migrated.openedAt)
    }
}
