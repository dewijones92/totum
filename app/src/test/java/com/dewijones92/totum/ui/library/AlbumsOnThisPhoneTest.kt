package com.dewijones92.totum.ui.library

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.music.MusicAlbumViewModel
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AlbumsOnThisPhoneTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloadManager()
    private val recital = AlbumRef("MPREb_x", "Guitar Recital")
    private val other = AlbumRef(null, "Soul of Spanish Guitar")

    private fun song(id: String, album: AlbumRef?, kind: MediaContentKind = MediaContentKind.MUSIC) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("music:album:x"),
            title = "song $id",
            publishedAt = null,
            duration = null,
            contentKind = kind,
            album = album,
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `downloaded songs gather by album, in the order they were downloaded, talk left out`() = runTest(dispatcher) {
        listOf(
            song("t1", recital),
            song("s1", other),
            song("t2", recital),
            song("talk", recital, MediaContentKind.STANDARD)
        )
            .forEach { downloads.download(it, audioOnly = true) }
        val controller = FakePlaybackController()
        val queue = PlaybackQueue(
            controller,
            VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
        )
        val model = LibraryViewModel(queue, downloads, fileSize = { 1L }, io = dispatcher)
        backgroundScope.launch { model.albums.collect { } }
        advanceUntilIdle()

        val albums = model.albums.value
        assertEquals(listOf("Guitar Recital", "Soul of Spanish Guitar"), albums.map { it.album.title })
        assertEquals(listOf("t1", "t2"), albums.first().songs.map { it.item.id.value })

        model.playAlbum(albums.first())
        advanceUntilIdle()
        val entries = queue.state.value.entries
        assertEquals(listOf("t1", "t2"), entries.map { it.item.item.id.value })
        assertEquals("Guitar Recital", entries.first().group?.title)
        assertEquals(
            "an album on the phone plays from its files",
            albums.first().songs.map {
                it.offline.handle
            },
            entries.map { it.item.handle }
        )

        model.deleteAlbum(albums.first())
        advanceUntilIdle()
        assertEquals(listOf("Soul of Spanish Guitar"), model.albums.value.map { it.album.title })
    }

    @Test
    fun `songs downloaded before the phone kept albums join theirs when the album page opens`() = runTest(dispatcher) {
        val old = song("t1", album = null, kind = MediaContentKind.STANDARD)
        downloads.download(old, audioOnly = true)
        val controller = FakePlaybackController()
        val queue = PlaybackQueue(
            controller,
            VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
        )
        val track =
            MusicSong(
                "t1",
                "song t1",
                "Pablo",
                "Guitar Recital",
                120,
                null,
                HttpUrl.of("https://www.youtube.com/watch?v=t1")
            )
        val album = MusicAlbum(
            "MPREb_x", "Guitar Recital", "Pablo", null, null, MusicReleaseKind.ALBUM, null, null, null, null,
            listOf(
                track
            ),
        )
        val catalogue = FakeYouTubeMusicCatalogue(albumsById = mapOf("MPREb_x" to album))
        val library = LibraryViewModel(queue, downloads, fileSize = { 1L }, io = dispatcher)
        backgroundScope.launch { library.albums.collect { } }
        advanceUntilIdle()
        assertEquals(emptyList<AlbumOnPhone>(), library.albums.value)

        MusicAlbumViewModel(
            MusicPage.Album("MPREb_x", title = "Guitar Recital"),
            catalogue,
            queue,
            downloads,
            MusicRadio(catalogue, queue, backgroundScope),
            backgroundScope,
        )
        advanceUntilIdle()

        assertEquals(listOf("Guitar Recital"), library.albums.value.map { it.album.title })
    }
}
