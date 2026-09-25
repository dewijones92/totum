package com.dewijones92.totum.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.channel.CheckedChannel
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

class RoomChannelLatestStoreTest {

    private lateinit var database: TotumDatabase
    private lateinit var store: RoomChannelLatestStore

    private val channelUrl = HttpUrl.of("https://www.youtube.com/channel/$CHANNEL")
    private val upload = MediaItem(
        id = MediaItemId("https://www.youtube.com/watch?v=aaaaaaaaaaa"),
        sourceId = SourceId(channelUrl.value),
        title = "The newest upload",
        publishedAt = Instant.parse("2026-09-24T09:00:00Z"),
        duration = null,
        author = "A Channel",
        thumbnailUrl = HttpUrl.of("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg"),
        mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=aaaaaaaaaaa"),
        sourceUrl = channelUrl,
    )
    private val checkedAt = Instant.parse("2026-09-24T10:00:00Z")

    @Before
    fun createStore() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        store = RoomChannelLatestStore(database.channelLatestDao())
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun anUploadComesBackAsTheSameItem() = runTest {
        store.put(listOf(CheckedChannel(CHANNEL, upload, checkedAt)))

        val read = store.observe().first().single()

        assertEquals(CHANNEL, read.channelId)
        assertEquals(checkedAt, read.checkedAt)
        assertEquals(upload, read.latest)
    }

    @Test
    fun aChannelWithNoUploadsIsRememberedAsChecked() = runTest {
        store.put(listOf(CheckedChannel(CHANNEL, null, checkedAt)))

        assertNull(store.observe().first().single().latest)
        assertEquals(mapOf(CHANNEL to checkedAt), store.checkedAt())
    }

    @Test
    fun aLaterCheckReplacesTheEarlierOne() = runTest {
        store.put(listOf(CheckedChannel(CHANNEL, null, checkedAt)))
        val later = checkedAt.plusSeconds(HOUR_S)

        store.put(listOf(CheckedChannel(CHANNEL, upload, later)))

        val read = store.observe().first().single()
        assertEquals(later, read.checkedAt)
        assertEquals(upload, read.latest)
    }

    private companion object {
        const val CHANNEL = "UCaaaaaaaaaaaaaaaaaaaaaa"
        const val HOUR_S = 3_600L
    }
}
