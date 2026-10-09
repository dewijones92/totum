package com.dewijones92.totum.ui.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.music.MusicRadio
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.common.albumPage
import com.dewijones92.totum.ui.common.artistPage
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoToArtistAndAlbumTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun song(
        author: String? = "Pablo Sáinz-Villegas",
        channel: String? = "UCqj1HtTq76Bo6rBXNwrF5Uw",
        album: AlbumRef? = AlbumRef("MPREb_ky8xEro8eK9", "Soñando España"),
        kind: MediaContentKind = MediaContentKind.MUSIC,
    ) = MediaItem(
        id = MediaItemId("Nn_CcTBataE"),
        sourceId = SourceId("music:radio:x"),
        title = "Sevillana",
        publishedAt = null,
        duration = null,
        author = author,
        contentKind = kind,
        sourceUrl = channel?.let { HttpUrl.of("https://www.youtube.com/channel/$it") },
        album = album,
    )

    @Test
    fun `a song goes to its artist's page by channel id and to its album by id`() {
        assertEquals(MusicPage.Artist("UCqj1HtTq76Bo6rBXNwrF5Uw", "Pablo Sáinz-Villegas"), song().artistPage())
        assertEquals(
            MusicPage.Album("MPREb_ky8xEro8eK9", title = "Soñando España", artist = "Pablo Sáinz-Villegas"),
            song().albumPage(),
        )
    }

    @Test
    fun `a song stored before it knew its links still goes there by name`() {
        val old = song(channel = null, album = AlbumRef(null, "Soñando España"))

        assertEquals(MusicPage.Artist(null, "Pablo Sáinz-Villegas"), old.artistPage())
        assertEquals(MusicPage.Album(null, title = "Soñando España", artist = "Pablo Sáinz-Villegas"), old.albumPage())
    }

    @Test
    fun `a video is not sent to a music page, and a song with no album offers none`() {
        assertNull(song(kind = MediaContentKind.STANDARD).artistPage())
        assertNull(song(kind = MediaContentKind.STANDARD).albumPage())
        assertNull(song(album = null).albumPage())
        assertNull(song(author = null, channel = null).artistPage())
    }

    @Test
    fun `an album known only by title is found by searching for it`() = runTest(dispatcher) {
        val album = MusicAlbum(
            "MPREb_ky8xEro8eK9", "Soñando España", "Pablo Sáinz-Villegas", null, null, MusicReleaseKind.ALBUM,
            null, null, null, "OLAK5uy_x", emptyList(),
        )
        val catalogue = FakeYouTubeMusicCatalogue(
            albumRefs = listOf(
                MusicAlbumRef("MPREb_other", "Soñando España (Live)", null, null, MusicReleaseKind.ALBUM, null),
                MusicAlbumRef("MPREb_ky8xEro8eK9", "Soñando España", null, null, MusicReleaseKind.ALBUM, null),
            ),
            albumsById = mapOf(album.browseId to album),
        )
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = FakePlaybackController()
        val queue = PlaybackQueue(
            controller = controller,
            launcher = VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            scope = work,
        )

        val vm = MusicAlbumViewModel(
            MusicPage.Album(null, title = "Soñando España", artist = "Pablo Sáinz-Villegas"),
            catalogue,
            queue,
            FakeDownloadManager(),
            MusicRadio(catalogue, queue, work),
            work,
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue("the album was not found: $state", state is MusicAlbumViewModel.State.Loaded)
        assertEquals("MPREb_ky8xEro8eK9", (state as MusicAlbumViewModel.State.Loaded).album.browseId)
        work.cancel()
    }
}
