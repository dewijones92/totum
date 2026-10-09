package com.dewijones92.totum.ui.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SeeAllTest {

    private val dispatcher = StandardTestDispatcher()
    private val listing = MusicListing("VLOLAK5uy_all", "ggMCCAI%3D")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun song(id: String) =
        MusicSong(id, "song $id", "The Beatles", null, 120, null, HttpUrl.of("https://www.youtube.com/watch?v=$id"))

    private fun TestScope.queue(): Pair<PlaybackQueue, CoroutineScope> {
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val controller = FakePlaybackController()
        val launcher = VideoPlaybackLauncher(
            VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
        )
        return PlaybackQueue(controller = controller, launcher = launcher, scope = work) to work
    }

    @Test
    fun `all songs arrive a page at a time and play on from the one tapped`() = runTest(dispatcher) {
        val catalogue = FakeYouTubeMusicCatalogue(
            listingPages = mapOf(
                null to Page(listOf(song("a1"), song("a2")), PageToken("p2")),
                "p2" to Page.last(listOf(song("a3"))),
            ),
        )
        val (queue, work) = queue()
        val vm = ArtistSongsViewModel(MusicPage.ArtistSongs(listing, "The Beatles"), catalogue, queue)
        advanceUntilIdle()

        assertEquals(
            listOf("a1", "a2"),
            (vm.state.value as ArtistSongsViewModel.State.Loaded).songs.map { it.id.value }
        )
        assertTrue((vm.state.value as ArtistSongsViewModel.State.Loaded).hasMore)

        vm.loadMore()
        advanceUntilIdle()
        val loaded = vm.state.value as ArtistSongsViewModel.State.Loaded
        assertEquals(listOf("a1", "a2", "a3"), loaded.songs.map { it.id.value })
        assertFalse(loaded.hasMore)

        vm.play(fromIndex = 1)
        advanceUntilIdle()
        val entries = queue.state.value.entries
        assertEquals(listOf("a2", "a3"), entries.map { it.item.item.id.value })
        assertEquals("The Beatles · All songs", entries.first().group?.title)
        assertTrue(entries.all { it.item.item.contentKind == MediaContentKind.MUSIC })
        work.cancel()
    }

    @Test
    fun `every album or single is listed to open`() = runTest(dispatcher) {
        val albums = listOf(
            MusicAlbumRef("MPREb_1", "Abbey Road", "The Beatles", "1969", MusicReleaseKind.ALBUM, null),
            MusicAlbumRef("MPREb_2", "Revolver", "The Beatles", "1966", MusicReleaseKind.ALBUM, null),
        )
        val catalogue = FakeYouTubeMusicCatalogue(releasesByListing = mapOf("MPADUC_x" to albums))

        val vm = ArtistReleasesViewModel(
            MusicPage.ArtistReleases(MusicListing("MPADUC_x", "p"), "The Beatles", singles = false),
            catalogue,
        )
        advanceUntilIdle()

        assertEquals(albums, (vm.state.value as ArtistReleasesViewModel.State.Loaded).releases)
    }
}
