package com.dewijones92.totum.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.AlbumRef
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
import org.junit.Test

class VideoIdMigrationTest {

    private companion object {
        const val NAME = "video-id-migration-test.db"
        const val BARE = "BNMKGYiJpvg"
        const val WATCH = "https://www.youtube.com/watch?v=$BARE"
        const val PODCAST_ID = "https://feed.test/episode.mp3"
        val ITEM_TABLES = listOf("queue_items", "play_history", "downloads", "local_playlist_items")
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var db: TotumDatabase? = null

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

    private fun item(id: String, album: AlbumRef? = null) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("music:radio"),
            title = "Feeling Good",
            publishedAt = null,
            duration = null,
            album = album,
        ),
        PlayHandle.Video(HttpUrl.of(WATCH)),
    )

    /** Writes [seed] at the current version, then rolls the file back to v25's shape and number. */
    private fun atV25(seed: suspend TotumDatabase.() -> Unit) {
        context.deleteDatabase(NAME)
        val fresh = open()
        runBlocking { fresh.seed() }
        val raw = fresh.openHelper.writableDatabase
        ITEM_TABLES.forEach { table ->
            raw.execSQL("ALTER TABLE $table DROP COLUMN albumId")
            raw.execSQL("ALTER TABLE $table DROP COLUMN albumTitle")
        }
        raw.execSQL("PRAGMA user_version = 25")
        fresh.close()
        db = null
    }

    private fun TotumDatabase.ids(table: String, key: String = "itemId"): List<String> =
        openHelper.readableDatabase.query("SELECT $key FROM $table ORDER BY $key").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    @Test
    fun aSongPlayedUnderBothIdsIsOneHistoryRowKeepingTheLaterPlay() = runBlocking {
        atV25 {
            RoomPlayHistoryStore(playHistoryDao()).record(item(BARE))
            RoomPlayHistoryStore(playHistoryDao()).record(item(WATCH))
            openHelper.writableDatabase.execSQL(
                "UPDATE play_history SET lastPlayedAtEpochMs = 1000 WHERE itemId = '$BARE'"
            )
            openHelper.writableDatabase.execSQL(
                "UPDATE play_history SET lastPlayedAtEpochMs = 2000 WHERE itemId = '$WATCH'"
            )
        }

        val migrated = open()

        assertEquals(listOf(BARE), migrated.ids("play_history"))
        assertEquals(
            "the later play was lost in the merge",
            listOf("2000"),
            migrated.openHelper.readableDatabase.query("SELECT lastPlayedAtEpochMs FROM play_history").use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            },
        )
    }

    @Test
    fun aFinishedDownloadWinsOverAnUnfinishedOneUnderTheOtherId() = runBlocking {
        atV25 {
            RoomDownloadStore(downloadDao()).put(item(BARE), DownloadState.Downloading(10, 100), audioOnly = true)
            RoomDownloadStore(
                downloadDao()
            ).put(item(WATCH), DownloadState.Downloaded("/data/song.m4a"), audioOnly = true)
        }

        val migrated = open()

        val downloaded = RoomDownloadStore(migrated.downloadDao()).observeDownloaded().first()
        assertEquals(listOf(BARE), migrated.ids("downloads"))
        assertEquals("/data/song.m4a", downloaded.single().localPath)
    }

    @Test
    fun aPlaylistHoldingBothIdsKeepsOneAndOtherPlaylistsAreUntouched() = runBlocking {
        lateinit var first: String
        lateinit var second: String
        atV25 {
            val store = RoomLocalPlaylistStore(localPlaylistDao())
            val a = store.create("Calm").also { first = it.value }
            val b = store.create("Later").also { second = it.value }
            store.addItem(a, item(BARE))
            store.addItem(a, item(WATCH))
            store.addItem(b, item(WATCH))
        }

        val migrated = open()

        val rows = migrated.openHelper.readableDatabase
            .query("SELECT playlistId, itemId FROM local_playlist_items ORDER BY playlistId").use {
                buildList { while (it.moveToNext()) add(it.getString(0) to it.getString(1)) }
            }
        assertEquals(listOf(first to BARE, second to BARE).sortedBy { it.first }, rows)
    }

    @Test
    fun progressKeepsTheNewerPositionAndTheQueueKeepsEveryEntry() = runBlocking {
        atV25 {
            RoomQueueStore(queueDao()).save(
                QueueSnapshot(listOf(QueueEntry(item(WATCH)), QueueEntry(item(PODCAST_ID))), currentIndex = 0),
            )
            val raw = openHelper.writableDatabase
            raw.execSQL("INSERT INTO playback_progress VALUES ('$BARE', 5000, 200000, 1000, NULL)")
            raw.execSQL("INSERT INTO playback_progress VALUES ('$WATCH', 9000, 200000, 2000, NULL)")
        }

        val migrated = open()

        assertEquals(listOf(BARE, PODCAST_ID).sorted(), migrated.ids("queue_items"))
        assertEquals(listOf(BARE), migrated.ids("playback_progress", key = "mediaItemId"))
        assertEquals(
            listOf("9000"),
            migrated.openHelper.readableDatabase.query("SELECT positionMs FROM playback_progress").use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            },
        )
    }

    @Test
    fun anItemsAlbumSurvivesStorageOnceMigrated() = runBlocking {
        atV25 {}
        val migrated = open()
        val store = RoomPlayHistoryStore(migrated.playHistoryDao())

        store.record(item(BARE, AlbumRef("MPREb_ky8xEro8eK9", "Guitar Recital")))

        assertEquals(AlbumRef("MPREb_ky8xEro8eK9", "Guitar Recital"), store.observe().first().single().item.album)
    }
}
