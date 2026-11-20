package com.dewijones92.totum.data.discover

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicArtistRef
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private fun song(id: String, artist: String) = MusicSong(
    videoId = id,
    title = "song $id",
    artist = artist,
    album = null,
    durationSeconds = 200,
    thumbnailUrl = null,
    watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
)

class DiscoverWalkTest {

    private class Graph(vararg nodes: Node) {
        data class Node(val id: String, val similar: List<String>, val songs: Int = 6)

        private val byId = nodes.associateBy { it.id }
        val asked = mutableListOf<String>()
        val failing = mutableSetOf<String>()

        suspend fun page(id: String): MusicResult<MusicArtist> {
            asked += id
            if (id in failing) return MusicResult.Failure("503")
            val node = byId[id] ?: return MusicResult.Failure("no artist $id")
            return MusicResult.Success(artist(node))
        }

        private fun artist(node: Node) = MusicArtist(
            browseId = node.id,
            name = "Artist ${node.id}",
            description = null,
            thumbnailUrl = null,
            topSongs = (1..node.songs).map { song("${node.id}-$it", "Artist ${node.id}") },
            allSongs = null,
            allAlbums = null,
            allSingles = null,
            albums = emptyList(),
            singles = emptyList(),
            similar = node.similar.map { MusicArtistRef(it, "Artist $it", null, null) },
            radio = null,
            shuffle = null,
        )
    }

    private val seed = DiscoverArtist("S", "Artist S")

    private fun walk(random: Random = Random(7), memory: Int = 40, offered: Set<String> = emptySet()) =
        DiscoverWalk(seed, random, memory = memory, offeredSongs = offered)

    private fun DiscoverStep.artists(): List<String> = picks.map { it.artist.id }

    @Test
    fun `the first step stays one hop from the seed`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A", "B")),
            Graph.Node("A", listOf("C")),
            Graph.Node("B", listOf("D")),
            Graph.Node("C", emptyList()),
            Graph.Node("D", emptyList()),
        )

        val step = walk().step(pages = 2, graph::page, DiscoverTaste.NOTHING)

        assertEquals(setOf("A", "B"), step.artists().toSet())
        assertTrue(step.picks.all { it.hop == 1 })
    }

    @Test
    fun `later steps widen to artists two hops out`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A", "B")),
            Graph.Node("A", listOf("C")),
            Graph.Node("B", listOf("D")),
            Graph.Node("C", emptyList()),
            Graph.Node("D", emptyList()),
        )
        val walk = walk()

        val first = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)
        val second = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)

        assertEquals(setOf("A", "B"), first.artists().toSet())
        assertEquals(setOf("C", "D"), second.artists().toSet())
        assertTrue(second.picks.all { it.hop == 2 })
    }

    @Test
    fun `a step mixes one hop and two hops once it may`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A1", "A2", "A3", "A4", "A5", "A6")),
            *("A1 A2 A3 A4 A5 A6".split(" ").map { Graph.Node(it, listOf("$it-x", "$it-y")) }).toTypedArray(),
            *("A1 A2 A3 A4 A5 A6".split(" ").flatMap { listOf("$it-x", "$it-y") }.map { Graph.Node(it, emptyList()) })
                .toTypedArray(),
        )
        val walk = walk()
        walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)

        val later = (1..3).flatMap { walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING).picks }

        val hops = later.map { it.hop }.toSet()
        assertEquals("both distances turn up once widened: $later", setOf(1, 2), hops)
    }

    @Test
    fun `artists played or liked recently are skipped, by id and by name`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A", "B", "C", "D")),
            Graph.Node("A", emptyList()),
            Graph.Node("B", emptyList()),
            Graph.Node("C", emptyList()),
            Graph.Node("D", emptyList()),
        )
        val taste = DiscoverTaste(
            recent = listOf(
                TasteMark(id = "A", name = null, why = "played recently"),
                TasteMark(id = null, name = "  ARTIST b ", why = "starred"),
            ),
        )

        val step = walk().step(pages = 2, graph::page, taste)

        assertEquals(setOf("C", "D"), step.artists().toSet())
        assertFalse("A and B were never even fetched", "A" in graph.asked || "B" in graph.asked)
    }

    @Test
    fun `artists you follow are used only when nothing else is in range`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("F", "N1", "N2")),
            Graph.Node("F", emptyList()),
            Graph.Node("N1", emptyList()),
            Graph.Node("N2", emptyList()),
        )
        val taste = DiscoverTaste(followed = listOf(TasteMark("F", null, "you follow them")))
        val walk = walk()

        val first = walk.step(pages = 2, graph::page, taste)
        val second = walk.step(pages = 2, graph::page, taste)

        assertEquals(setOf("N1", "N2"), first.artists().toSet())
        assertEquals("the followed artist is the fallback, not the first choice", listOf("F"), second.artists())
    }

    @Test
    fun `the same artist never plays twice in a row, even with no memory of the session`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A")),
            Graph.Node("A", listOf("B")),
            Graph.Node("B", listOf("A")),
        )
        val walk = walk(memory = 0)

        val picks = (1..6).flatMap { walk.step(pages = 1, graph::page, DiscoverTaste.NOTHING).picks }

        assertTrue("it kept going: ${picks.map { it.artist.id }}", picks.size >= 5)
        picks.zipWithNext().forEach { (a, b) -> assertTrue("${a.artist.id} twice in a row", a.artist != b.artist) }
    }

    @Test
    fun `an artist is not offered again within the session`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A", "B")),
            Graph.Node("A", listOf("B")),
            Graph.Node("B", listOf("A")),
        )
        val walk = walk()

        val picks = (1..3).flatMap { walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING).picks }

        assertEquals(picks.map { it.artist.id }.toSet().size, picks.size)
    }

    @Test
    fun `a walk with nothing near the seed stops politely and asks for nothing more`() = runBlocking {
        val graph = Graph(Graph.Node("S", emptyList()))
        val walk = walk()

        val first = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)
        val second = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)

        assertTrue(first.picks.isEmpty())
        assertTrue(first.dry)
        assertTrue(second.dry)
        assertEquals("the seed's page once, then nothing", listOf("S"), graph.asked)
    }

    @Test
    fun `a seed page that fails is a transient failure, not a dry walk`() = runBlocking {
        val graph = Graph(Graph.Node("S", listOf("A")), Graph.Node("A", emptyList()))
        graph.failing += "S"
        val walk = walk()

        val failed = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)
        graph.failing.clear()
        val retried = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)

        assertFalse(failed.dry)
        assertEquals("a failed seed page is reported, not read as nothing found", "503", failed.failure)
        assertEquals(1, failed.pagesAsked)
        assertEquals(listOf("A"), retried.artists())
    }

    @Test
    fun `a step whose every page fails reports the failure rather than an empty range`() = runBlocking {
        val graph = Graph(Graph.Node("S", listOf("A", "B")), Graph.Node("A", emptyList()), Graph.Node("B", emptyList()))
        graph.failing += setOf("A", "B")

        val step = walk().step(pages = 2, graph::page, DiscoverTaste.NOTHING)

        assertTrue(step.picks.isEmpty())
        assertEquals("503", step.failure)
        assertFalse("not dry: the artists exist, YouTube just did not answer", step.dry)
    }

    @Test
    fun `a step that finds songs reports no failure, and an empty range is dry not failed`() = runBlocking {
        val graph = Graph(Graph.Node("S", listOf("A")), Graph.Node("A", emptyList()))
        val ok = walk().step(pages = 2, graph::page, DiscoverTaste.NOTHING)
        val empty = walk().step(pages = 2, Graph(Graph.Node("S", emptyList()))::page, DiscoverTaste.NOTHING)

        assertEquals(null, ok.failure)
        assertEquals(null, empty.failure)
        assertTrue(empty.dry)
    }

    @Test
    fun `a step asks for at most the pages it was given, plus the seed page the first time`() = runBlocking {
        val ids = (1..30).map { "A$it" }
        val graph = Graph(
            Graph.Node("S", ids.take(10)),
            *ids.map { Graph.Node(it, ids.shuffled(Random(it.hashCode())).take(8)) }.toTypedArray(),
        )
        val walk = walk()

        val first = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)
        val firstCalls = graph.asked.size
        val steps = (1..6).map {
            val before = graph.asked.size
            walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING) to graph.asked.size - before
        }

        assertTrue("first step asked $firstCalls", firstCalls <= 3)
        assertEquals(firstCalls, first.pagesAsked)
        steps.forEach { (step, calls) ->
            assertTrue("a top-up asked $calls pages", calls <= 2)
            assertEquals(calls, step.pagesAsked)
        }
    }

    @Test
    fun `a page that fails or has nothing to play uses up a page but is not asked again`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("Bad", "Empty", "Good1", "Good2")),
            Graph.Node("Bad", emptyList()),
            Graph.Node("Empty", emptyList(), songs = 0),
            Graph.Node("Good1", emptyList()),
            Graph.Node("Good2", emptyList()),
        )
        graph.failing += "Bad"
        val walk = DiscoverWalk(seed, Random(1))
        val allAsked = mutableListOf<String>()

        repeat(4) {
            val step = walk.step(pages = 2, graph::page, DiscoverTaste.NOTHING)
            assertTrue(step.pagesAsked <= 3)
            allAsked += step.picks.map { it.artist.id }
        }

        assertEquals(setOf("Good1", "Good2"), allAsked.toSet())
        assertEquals("each artist's page asked at most once", graph.asked.size, graph.asked.toSet().size)
    }

    @Test
    fun `the song comes from an artist's top songs, never from far down the list`() = runBlocking {
        val taken = mutableSetOf<String>()
        repeat(60) { round ->
            val graph = Graph(Graph.Node("S", listOf("A")), Graph.Node("A", emptyList(), songs = 12))
            val step = DiscoverWalk(seed, Random(round)).step(pages = 1, graph::page, DiscoverTaste.NOTHING)
            taken += step.picks.map { it.song.videoId }
        }

        assertTrue("only the top five: $taken", taken.all { it.removePrefix("A-").toInt() <= DiscoverWalk.TOP_SONGS })
        assertTrue("it is random among them: $taken", taken.size > 1)
    }

    @Test
    fun `songs that are not wanted or already offered are passed over`() = runBlocking {
        val graph = Graph(Graph.Node("S", listOf("A")), Graph.Node("A", emptyList(), songs = 8))
        val walk = walk(offered = setOf("A-1", "A-2"))

        val step = walk.step(
            pages = 1,
            graph::page,
            DiscoverTaste.NOTHING,
            songWanted = { it.videoId != "A-3" },
        )

        val chosen = step.picks.single().song.videoId
        assertTrue("$chosen was one of the unwanted", chosen in setOf("A-4", "A-5", "A-6", "A-7", "A-8"))
    }

    @Test
    fun `when everything in range is spent the walk recentres on the last artist it played`() = runBlocking {
        val graph = Graph(
            Graph.Node("S", listOf("A")),
            Graph.Node("A", listOf("B")),
            Graph.Node("B", listOf("C")),
            Graph.Node("C", emptyList()),
        )
        val walk = walk()

        val all = (1..4).flatMap { walk.step(pages = 1, graph::page, DiscoverTaste.NOTHING).picks }

        assertEquals(listOf("A", "B", "C"), all.map { it.artist.id })
    }

    @Test
    fun `every pick is logged with the artist, the hop, the song and why the others were skipped`() {
        val lines = mutableListOf<String>()
        val before = Diag.sink
        Diag.sink = Diag.Sink { _, tag, message, _ -> if (tag == "discover") lines += message }
        try {
            val graph = Graph(
                Graph.Node("S", listOf("A", "B", "C")),
                Graph.Node("A", emptyList()),
                Graph.Node("B", emptyList()),
                Graph.Node("C", emptyList()),
            )
            val taste = DiscoverTaste(recent = listOf(TasteMark("A", null, "played recently")))

            runBlocking { walk().step(pages = 1, graph::page, taste) }

            val pick = lines.single { "picked" in it }
            assertTrue(pick, "Artist " in pick && "hop 1" in pick && "song " in pick)
            assertTrue(
                "the skipped artist and its reason are named: $lines",
                lines.any {
                    "Artist A" in it && "played recently" in it
                }
            )
        } finally {
            Diag.sink = before
        }
    }
}
