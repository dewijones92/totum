package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ShuffleAllPlaysAtOnceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private val listing = MusicListing("VLchopin", null)
    private val target = ArtistSongs("Chopin", listing, artUrl = null, artistId = "UC-chopin")

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun song(id: String) =
        MusicSong(id, "song $id", "Chopin", null, 120, null, HttpUrl.of("https://www.youtube.com/watch?v=$id"))

    private fun ref(id: String) = MusicAlbumRef(id, "album $id", "Chopin", null, MusicReleaseKind.ALBUM, null)

    private fun album(id: String, vararg tracks: String) = MusicAlbum(
        id, "album $id", "Chopin", null, null, MusicReleaseKind.ALBUM, null, null, null, null, tracks.map(::song),
    )

    private val fake = FakeYouTubeMusicCatalogue(
        listingPages = mapOf(null to Page.last(listOf(song("top1"), song("top2")))),
        artistsById = mapOf(
            "UC-chopin" to MusicArtist(
                browseId = "UC-chopin",
                name = "Chopin",
                description = null,
                thumbnailUrl = null,
                topSongs = emptyList(),
                allSongs = listing,
                allAlbums = null,
                allSingles = null,
                albums = listOf(ref("nocturnes"), ref("etudes")),
                singles = emptyList(),
                similar = emptyList(),
                radio = null,
                shuffle = null,
            ),
        ),
        albumsById = mapOf(
            "nocturnes" to album("nocturnes", "n1", "n2"),
            "etudes" to album("etudes", "e1", "top1"),
        ),
    )

    private val albumsMayAnswer = CompletableDeferred<Unit>()
    private val albumsAsked = mutableListOf<String>()

    private val slowAlbums = object : YouTubeMusicCatalogue by fake {
        override suspend fun album(browseId: String): MusicResult<MusicAlbum> {
            albumsAsked += browseId
            albumsMayAnswer.await()
            return fake.album(browseId)
        }
    }

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

    private fun TestScope.kept() = KeptArtistSongs(folder.root, clock = { 1_000L }, io = dispatcher)

    @Test
    fun `music starts from the top songs before the albums arrive, and the rest is shuffled in behind it`() =
        runTest(dispatcher) {
            val (queue, work) = queue()
            val operations = Operations()
            val ended = mutableListOf<Ended>()
            work.launch { operations.ended.collect { ended += it } }
            val shuffle = ArtistShuffle(
                slowAlbums,
                queue,
                recent = null,
                kept = kept(),
                operations = operations,
                scope = work,
            )

            val playing = async { shuffle.shuffleAll(target) }
            advanceUntilIdle()

            assertEquals("plays as soon as the top songs are in", 2, playing.await())
            assertEquals(setOf("top1", "top2"), queue.state.value.entries.map { it.item.item.id.value }.toSet())
            assertTrue("the albums are still being walked", albumsAsked.isNotEmpty())
            val live = operations.running.value.single()
            assertTrue("the strip says it is already playing", live.playing)
            assertEquals(2, live.steps)

            albumsMayAnswer.complete(Unit)
            advanceUntilIdle()

            val entries = queue.state.value.entries
            assertEquals(setOf("top1", "top2", "n1", "n2", "e1"), entries.map { it.item.item.id.value }.toSet())
            assertEquals(5, entries.size)
            assertTrue(entries.all { it.group?.title == "Chopin · All songs" })
            assertTrue(operations.running.value.isEmpty())
            assertEquals(OperationEnd.Done(5), ended.single().end)
            work.cancel()
        }

    @Test
    fun `a second shuffle of the same artist plays the kept list at once and asks YouTube nothing`() =
        runTest(dispatcher) {
            albumsMayAnswer.complete(Unit)
            val (queue, work) = queue()
            ArtistShuffle(slowAlbums, queue, recent = null, kept = kept(), scope = work).shuffleAll(target)
            advanceUntilIdle()
            queue.clear()
            val refusing = FakeYouTubeMusicCatalogue()

            val count = ArtistShuffle(refusing, queue, recent = null, kept = kept(), scope = work).shuffleAll(target)
            advanceUntilIdle()

            assertEquals(5, count)
            assertEquals(5, queue.state.value.entries.size)
            work.cancel()
        }

    @Test
    fun `songs found after the group was removed are not put back`() = runTest(dispatcher) {
        val (queue, work) = queue()
        val shuffle = ArtistShuffle(slowAlbums, queue, recent = null, scope = work)

        shuffle.shuffleAll(target)
        advanceUntilIdle()
        queue.clear()
        albumsMayAnswer.complete(Unit)
        advanceUntilIdle()

        assertTrue(queue.state.value.entries.isEmpty())
        work.cancel()
    }
}
