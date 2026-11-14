package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.discover.DiscoverArtist
import com.dewijones92.totum.data.discover.DiscoverTaste
import com.dewijones92.totum.data.discover.DiscoverWalk
import com.dewijones92.totum.data.discover.TasteMark
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicArtistRef
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.RecentActions
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MusicDiscoverTest {

    private fun song(artist: String, n: Int) = MusicSong(
        videoId = "$artist$n",
        title = "song $artist$n",
        artist = "Artist $artist",
        album = null,
        durationSeconds = 200,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$artist$n"),
        artistId = artist,
    )

    private fun artist(id: String, similar: List<String>) = MusicArtist(
        browseId = id,
        name = "Artist $id",
        description = null,
        thumbnailUrl = null,
        topSongs = (1..6).map { song(id, it) },
        allSongs = null,
        allAlbums = null,
        allSingles = null,
        albums = emptyList(),
        singles = emptyList(),
        similar = similar.map { MusicArtistRef(it, "Artist $it", null, null) },
        radio = null,
        shuffle = null,
    )

    private fun catalogue(vararg nodes: Pair<String, List<String>>) =
        FakeYouTubeMusicCatalogue(artistsById = nodes.associate { (id, similar) -> id to artist(id, similar) })

    private val wide = arrayOf(
        "S" to listOf("A", "B", "C", "D", "E"),
        "A" to listOf("F", "G"),
        "B" to listOf("H", "I"),
        "C" to listOf("J", "K"),
        "D" to listOf("L", "M"),
        "E" to listOf("N", "O"),
        "F" to listOf("A"),
        "G" to listOf("A"),
        "H" to listOf("A"),
        "I" to listOf("A"),
        "J" to listOf("A"),
        "K" to listOf("A"),
        "L" to listOf("A"),
        "M" to listOf("A"),
        "N" to listOf("A"),
        "O" to listOf("A"),
    )

    private class Rig(scope: TestScope, val catalogue: FakeYouTubeMusicCatalogue) {
        val work = CoroutineScope(scope.coroutineContext + SupervisorJob())
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
        val recent = RecentActions()
        val operations = Operations()
        val tasteSource = DiscoverTasteSource(
            history = { emptyList() },
            starred = { emptyList() },
            listens = { emptyList() },
            recentActions = { emptyList() },
            followed = { FollowedArtists() },
        )
        val engine = Discover(catalogue, tasteSource, random = Random(11))
        val radio = MusicRadio(catalogue, queue, work, recent, discover = engine, operations = operations)

        fun authors(): List<String> = queue.state.value.entries.filter { it.group?.id == radio.activeGroupId }
            .map { it.item.item.author.orEmpty() }
    }

    @Test
    fun `starting a Discover queues a group named after the seed, and records it in Recently`() = runTest {
        val rig = Rig(this, catalogue(*wide))

        val started = rig.radio.startDiscover(DiscoverSeed("Seed song", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()

        assertTrue(started)
        val group = rig.queue.state.value.entries.first().group
        assertEquals("🔭 Discover from Seed song", group?.title)
        assertTrue(group!!.id.startsWith(DISCOVER_GROUP_PREFIX))
        val pin = rig.recent.recent.value.single() as Pin.Discover
        assertEquals("discover:S", pin.key)
        assertEquals("Seed song", pin.title)
        rig.work.cancel()
    }

    @Test
    fun `the group tops itself up as it is played, never the same artist twice in a row`() = runTest {
        val rig = Rig(this, catalogue(*wide))
        rig.radio.startDiscover(DiscoverSeed("Seed", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()
        val first = rig.authors().size

        rig.queue.jumpTo(rig.authors().size - 1)
        advanceUntilIdle()

        val authors = rig.authors()
        assertTrue("it topped up: $first -> ${authors.size}", authors.size > first)
        authors.zipWithNext().forEach { (a, b) -> assertTrue("$a twice in a row in $authors", a != b) }
        assertFalse("the seed's own songs are not offered", "Artist S" in authors)
        rig.work.cancel()
    }

    @Test
    fun `a whole session never asks for more pages than its plan, and never asks for one twice`() = runTest {
        val rig = Rig(this, catalogue(*wide))
        rig.radio.startDiscover(DiscoverSeed("Seed", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()
        repeat(4) {
            rig.queue.jumpTo(rig.authors().size - 1)
            advanceUntilIdle()
        }

        val asked = rig.catalogue.artistRequests
        assertEquals("no artist page asked twice: $asked", asked.size, asked.toSet().size)
        assertTrue("seed page is asked once: $asked", asked.count { it == "S" } == 1)
        rig.work.cancel()
    }

    @Test
    fun `one Discover step asks for at most the pages it was planned`() = runTest {
        val rig = Rig(this, catalogue(*wide))
        val walk = rig.engine.walkFrom(DiscoverArtist("S", "Artist S"), "Seed")

        val first = rig.engine.step(walk, DiscoverWalk.PAGES_PER_TOP_UP)
        val afterFirst = rig.catalogue.artistRequests.size
        val second = rig.engine.step(walk, DiscoverWalk.PAGES_PER_TOP_UP)
        val afterSecond = rig.catalogue.artistRequests.size

        assertTrue("first step asked $afterFirst", afterFirst <= 1 + DiscoverWalk.PAGES_PER_TOP_UP)
        val secondAsked = afterSecond - afterFirst
        assertTrue("second step asked $secondAsked", secondAsked <= DiscoverWalk.PAGES_PER_TOP_UP)
        assertEquals(afterFirst, first.pagesAsked)
        assertEquals(secondAsked, second.pagesAsked)
        rig.work.cancel()
    }

    @Test
    fun `artist pages are kept, so a second look at one asks nothing`() = runTest {
        val rig = Rig(this, catalogue("S" to listOf("A")))
        val pages = ArtistPages(rig.catalogue)

        pages.get("S")
        pages.get("S")

        assertEquals(listOf("S"), rig.catalogue.artistRequests)
        rig.work.cancel()
    }

    @Test
    fun `a seed with nothing near it queues nothing, says so, and ends the operation as nothing`() = runTest {
        val rig = Rig(this, catalogue("S" to emptyList()))
        val ended = mutableListOf<OperationEnd>()
        val watcher = launchEnded(rig, ended)

        val started = rig.radio.startDiscover(DiscoverSeed("Lonely", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()

        assertFalse(started)
        assertTrue(rig.queue.state.value.entries.isEmpty())
        assertEquals(listOf<OperationEnd>(OperationEnd.Nothing), ended)
        assertTrue(rig.recent.recent.value.isEmpty())
        watcher.cancel()
        rig.work.cancel()
    }

    @Test
    fun `a Discover carries on after the app restarts, from its saved artist, still topping itself up`() = runTest {
        val file = kotlin.io.path.createTempFile("radio", ".txt").toFile().apply { delete() }
        val rig = Rig(this, catalogue(*wide))
        val first = MusicRadio(rig.catalogue, rig.queue, rig.work, rig.recent, file, discover = rig.engine)
        first.startDiscover(DiscoverSeed("Seed", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()
        val queuedBefore = rig.queue.state.value.entries.size
        val saved = file.readLines()

        val again = MusicRadio(
            rig.catalogue,
            rig.queue,
            rig.work,
            rig.recent,
            file,
            discover = Discover(rig.catalogue, rig.tasteSource, random = Random(5)),
        )
        advanceUntilIdle()

        assertEquals("discover", saved[7])
        assertEquals("S", saved[8])
        assertEquals("Artist S", saved[9])
        assertEquals("radio:discover:S", again.activeGroupId)
        assertTrue(again.describe(), again.isActive)
        rig.queue.jumpTo(rig.queue.state.value.entries.size - 1)
        advanceUntilIdle()
        assertTrue("it topped up after the restart", rig.queue.state.value.entries.size > queuedBefore)
        val authors = rig.queue.state.value.entries.map { it.item.item.author.orEmpty() }
        authors.zipWithNext().forEach { (a, b) -> assertTrue("$a twice in a row in $authors", a != b) }
        rig.work.cancel()
    }

    @Test
    fun `a saved Discover with no discover engine here is dropped, not restored as a radio`() = runTest {
        val file = kotlin.io.path.createTempFile("radio", ".txt").toFile().apply { delete() }
        val rig = Rig(this, catalogue(*wide))
        val first = MusicRadio(rig.catalogue, rig.queue, rig.work, rig.recent, file, discover = rig.engine)
        first.startDiscover(DiscoverSeed("Seed", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()

        val without = MusicRadio(rig.catalogue, rig.queue, rig.work, rig.recent, file)
        advanceUntilIdle()

        assertFalse(without.isActive)
        rig.work.cancel()
    }

    @Test
    fun `YouTube Music not answering is a failure after one polite retry, never nothing found`() = runTest {
        val rig = Rig(this, catalogue(*wide))
        rig.catalogue.failNextArtistRequests = 100
        val ended = mutableListOf<OperationEnd>()
        val watcher = launchEnded(rig, ended)

        val started = rig.radio.startDiscover(DiscoverSeed("Seed", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()

        assertFalse(started)
        assertTrue(rig.queue.state.value.entries.isEmpty())
        val end = ended.single()
        assertTrue("ended as $end", end is OperationEnd.Failed && "reach" in end.why)
        assertEquals("the seed page asked once, then once more", listOf("S", "S"), rig.catalogue.artistRequests)
        assertTrue(rig.recent.recent.value.isEmpty())
        watcher.cancel()
        rig.work.cancel()
    }

    @Test
    fun `one dropped request is retried and the Discover then starts`() = runTest {
        val rig = Rig(this, catalogue(*wide))
        rig.catalogue.failNextArtistRequests = 1

        val started = rig.radio.startDiscover(DiscoverSeed("Seed", artistId = "S", artistName = "Artist S"))
        advanceUntilIdle()

        assertTrue(started)
        assertTrue(rig.queue.state.value.entries.isNotEmpty())
        rig.work.cancel()
    }

    @Test
    fun `a seed that cannot be looked up is a failure too`() = runTest {
        val rig = Rig(this, catalogue(*wide))
        val ended = mutableListOf<OperationEnd>()
        val watcher = launchEnded(rig, ended)

        val started = rig.radio.startDiscover(DiscoverSeed("Gone", albumId = "MPREb_missing"))
        advanceUntilIdle()

        assertFalse(started)
        assertTrue("ended as ${ended.single()}", ended.single() is OperationEnd.Failed)
        watcher.cancel()
        rig.work.cancel()
    }

    @Test
    fun `a seed with no artist id is found by name before the walk starts`() = runTest {
        val rig = Rig(
            this,
            catalogue(*wide).also {
                it.artistRefs = listOf(MusicArtistRef("S", "artist s", null, null))
            },
        )

        val started = rig.radio.startDiscover(DiscoverSeed("Artist S", artistName = "Artist S"))
        advanceUntilIdle()

        assertTrue(started)
        assertTrue("S" in rig.catalogue.artistRequests)
        rig.work.cancel()
    }

    @Test
    fun `recently played artists are not offered`() = runTest {
        val recent = DiscoverTaste(recent = listOf(TasteMark("A", null, "played recently")))
        val catalogue = catalogue(*wide)
        val walk = DiscoverWalk(DiscoverArtist("S", "Artist S"), Random(3))

        val step = walk.step(pages = 2, page = { catalogue.artist(it) }, taste = recent)

        assertFalse(step.picks.any { it.artist.id == "A" })
        assertFalse("A never asked", "A" in catalogue.artistRequests)
    }

    private fun TestScope.launchEnded(rig: Rig, into: MutableList<OperationEnd>) =
        launch(Dispatchers.Unconfined) { rig.operations.ended.collect { into += it.end } }
}
