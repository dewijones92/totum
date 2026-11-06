package com.dewijones92.totum.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RoomUndoTest {

    private lateinit var database: TotumDatabase
    private lateinit var playlists: RoomLocalPlaylistStore
    private lateinit var history: RoomPlayHistoryStore

    private fun video(id: String) = PlayableItem(
        item = MediaItem(MediaItemId(id), SourceId("ch"), id, publishedAt = null, duration = null),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    @Before
    fun createStores() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        playlists = RoomLocalPlaylistStore(database.localPlaylistDao())
        history = RoomPlayHistoryStore(database.playHistoryDao(), limit = 3)
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun aDeletedPlaylistComesBackWithItsItemsAndItsPlaceInTheList() = runTest {
        val first = playlists.create("First")
        Thread.sleep(CLOCK_TICK_MS)
        val middle = playlists.create("Middle")
        Thread.sleep(CLOCK_TICK_MS)
        val last = playlists.create("Last")
        listOf("a", "b", "c").forEach { playlists.addItem(middle, video(it)) }

        val removed = playlists.delete(middle)!!
        assertEquals(listOf(last, first), playlists.observePlaylists().first().map { it.id })

        playlists.restore(removed)

        assertEquals(listOf(last, middle, first), playlists.observePlaylists().first().map { it.id })
        assertEquals(listOf("a", "b", "c"), playlists.observeItems(middle).first().map { it.item.id.value })
    }

    @Test
    fun deletingAPlaylistThatIsGoneReturnsNothing() = runTest {
        assertNull(playlists.delete(com.dewijones92.totum.domain.PlaylistId("gone")))
    }

    @Test
    fun aClearedHistoryComesBackInOrderBehindWhatWasPlayedMeanwhile() = runTest {
        listOf("a", "b").forEach {
            history.record(video(it))
            Thread.sleep(CLOCK_TICK_MS)
        }
        val cleared = history.clear()
        assertEquals(listOf("b", "a"), cleared.map { it.item.id.value })
        assertEquals(emptyList<String>(), history.observe().first().map { it.item.id.value })

        history.record(video("new"))
        history.restore(cleared)

        assertEquals(listOf("new", "b", "a"), history.observe().first().map { it.item.id.value })
    }

    @Test
    fun restoringTheHistoryRespectsTheCap() = runTest {
        listOf("a", "b", "c").forEach {
            history.record(video(it))
            Thread.sleep(CLOCK_TICK_MS)
        }
        val cleared = history.clear()
        history.record(video("x"))
        Thread.sleep(CLOCK_TICK_MS)
        history.record(video("y"))

        history.restore(cleared)

        assertEquals(listOf("y", "x", "c"), history.observe().first().map { it.item.id.value })
    }

    private companion object {
        const val CLOCK_TICK_MS = 5L
    }
}
