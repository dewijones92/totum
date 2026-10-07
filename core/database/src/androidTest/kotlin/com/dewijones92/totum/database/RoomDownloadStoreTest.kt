package com.dewijones92.totum.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.placeholderTitleFor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class RoomDownloadStoreTest {

    private lateinit var database: TotumDatabase
    private lateinit var store: RoomDownloadStore
    private val id = MediaItemId("ep-1")

    private val episode = PlayableItem(
        MediaItem(
            id = id,
            sourceId = SourceId("feed-1"),
            title = "Episode one",
            publishedAt = null,
            duration = null,
            author = "A host",
            mediaUrl = HttpUrl.of("https://cdn.example.com/ep1.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private val video = PlayableItem(
        MediaItem(
            id = MediaItemId("vid-1"),
            sourceId = SourceId("https://www.youtube.com/@chan"),
            title = "A video",
            publishedAt = null,
            duration = null,
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=abc123")),
    )

    @Before
    fun createStore() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            TotumDatabase::class.java,
        ).build()
        store = RoomDownloadStore(database.downloadDao())
    }

    @After
    fun closeDatabase() = database.close()

    @Test
    fun learningATitleKeepsTheFileAndSurvivesAnOlderDownloadWrite() = runTest {
        val pending = video.copy(item = video.item.copy(title = placeholderTitleFor(video.item.id)))
        val state = DownloadState.Downloaded("/data/video.media", audioOnly = true)
        store.put(pending, state, audioOnly = true)
        store.learnFacts(video.item)
        store.put(pending, state, audioOnly = true)

        val saved = store.observeDownloaded().first().single()
        assertEquals(video.item.title, saved.item.title)
        assertEquals(video.item.author, saved.item.author)
        assertEquals(state.localPath, saved.localPath)
        assertTrue(saved.audioOnly)
        assertEquals(pending.handle, store.request(video.item.id)?.item?.handle)
    }

    @Test
    fun learningFactsDoesNotResurrectADeletedDownload() = runTest {
        store.put(video, DownloadState.Downloaded("/data/video.media"), audioOnly = false)
        store.remove(video.item.id)
        store.learnFacts(video.item)

        assertTrue(store.observeDownloaded().first().isEmpty())
    }

    @Test
    fun statesRoundTripThroughEachStage() = runTest {
        store.put(episode, DownloadState.Downloading(500, 1000), audioOnly = false)
        assertEquals(DownloadState.Downloading(500, 1000), store.get(id))

        store.put(episode, DownloadState.Downloaded("/data/ep1.media"), audioOnly = false)
        assertEquals(DownloadState.Downloaded("/data/ep1.media"), store.get(id))
        assertEquals(mapOf(id to DownloadState.Downloaded("/data/ep1.media")), store.observeAll().first())

        store.remove(id)
        assertEquals(DownloadState.NotDownloaded, store.get(id))
    }

    @Test
    fun failureIsPersisted() = runTest {
        store.put(episode, DownloadState.Failed("HTTP 500"), audioOnly = false)
        assertEquals(DownloadState.Failed("HTTP 500"), store.get(id))
    }

    /** The gap this closes: a download used to be an id and nothing else. */
    @Test
    fun aNewDownloadIsUncutAndItsSegmentsSurviveAStateRewrite() = runTest {
        val segments = listOf(SkipSegment(10.seconds, 42.5.seconds), SkipSegment(60.seconds, 75.seconds))
        store.put(video, DownloadState.Downloaded("/data/vid.media"), audioOnly = false)
        store.rememberSkipSegments(video.item.id, segments)
        store.put(video, DownloadState.Downloaded("/data/vid.media"), audioOnly = false)

        val copy = store.observeDownloaded().first().single()
        assertEquals(false, copy.sponsorSegmentsCut)
        assertEquals(segments, copy.skipSegments)
    }

    @Test
    fun aCutDownloadSaysSo() = runTest {
        store.put(video, DownloadState.Downloaded("/data/vid.media", sponsorSegmentsCut = true), audioOnly = false)
        assertEquals(true, store.observeDownloaded().first().single().sponsorSegmentsCut)
        assertEquals(DownloadState.Downloaded("/data/vid.media", sponsorSegmentsCut = true), store.get(video.item.id))
    }

    @Test
    fun aFinishedDownloadKeepsTheItemAndItsPillar() = runTest {
        store.put(video, DownloadState.Downloaded("/data/vid.media", audioOnly = true), audioOnly = true)
        store.put(episode, DownloadState.Downloaded("/data/ep1.media"), audioOnly = false)

        val byId = store.observeDownloaded().first().associateBy { it.item.id.value }

        assertEquals(setOf("vid-1", "ep-1"), byId.keys)
        assertEquals("A video", byId.getValue("vid-1").item.title)
        assertEquals(MediaKind.VIDEO, byId.getValue("vid-1").pillar)
        assertTrue(byId.getValue("vid-1").audioOnly)
        assertEquals("A host", byId.getValue("ep-1").item.author)
        assertEquals(MediaKind.PODCAST, byId.getValue("ep-1").pillar)
    }

    @Test
    fun unfinishedDownloadsAreNotListedAsOffline() = runTest {
        store.put(episode, DownloadState.Downloading(1, 2), audioOnly = false)
        store.put(video, DownloadState.Failed("nope"), audioOnly = false)

        assertEquals(emptyList<String>(), store.observeDownloaded().first().map { it.item.id.value })
    }
}
