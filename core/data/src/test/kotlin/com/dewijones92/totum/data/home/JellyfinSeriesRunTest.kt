package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.jellyfin.toPlayable
import com.dewijones92.totum.data.queue.QueueGroup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinSeriesRunTest {

    private val stream = HttpUrl.of("https://pi.test/jellyfin/stream/x.mkv")

    private fun episode(id: String, season: Int, number: Int, series: String = "Bluey") = JellyfinItem(
        id = id,
        name = "Episode $number",
        kind = JellyfinKind.EPISODE,
        seriesName = series,
        seasonNumber = season,
        episodeNumber = number,
        container = "mkv",
    )

    private val library = mapOf<String?, List<JellyfinItem>>(
        null to listOf(
            JellyfinItem("film0000", "Heat", JellyfinKind.MOVIE),
            JellyfinItem("series00", "Bluey", JellyfinKind.SERIES),
        ),
        "series00" to listOf(
            JellyfinItem("season01", "Season 1", JellyfinKind.SEASON, seriesName = "Bluey"),
            JellyfinItem("season02", "Season 2", JellyfinKind.SEASON, seriesName = "Bluey"),
        ),
        "season01" to listOf(episode("ep0101", 1, 1), episode("ep0102", 1, 2), episode("ep0103", 1, 3)),
        "season02" to listOf(episode("ep0201", 2, 1), episode("ep0202", 2, 2)),
    )

    private class Api(
        private val library: Map<String?, List<JellyfinItem>>,
        private val searchable: Boolean = false,
    ) : JellyfinApi {
        val browsed = mutableListOf<String?>()
        val searched = mutableListOf<String>()

        override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> {
            browsed += parent
            val items = library[parent] ?: return HomeAnswer.No("no such folder")
            return HomeAnswer.Ok(JellyfinListing(parent, "listing", items))
        }

        override suspend fun search(term: String): HomeAnswer<JellyfinListing> {
            searched += term
            if (!searchable) return HomeAnswer.No(HomePiCall.NEEDS_UPDATING)
            val hits = library.values.flatten().filter { it.name.contains(term, ignoreCase = true) }
            return HomeAnswer.Ok(JellyfinListing(null, "🔎", hits, hits.size, null, search = term))
        }

        override fun streamUrl(item: JellyfinItem): HttpUrl =
            HttpUrl.of("https://pi.test/jellyfin/stream/${item.id}.mkv")
    }

    private fun tapped(season: Int, number: Int) =
        episode("ep0$season" + "0$number", season, number).toPlayable(stream).item

    private suspend fun run(api: JellyfinApi, season: Int, number: Int) = api.seriesRunOf(tapped(season, number))

    @Test
    fun `an episode is queued as its whole series in order, so it carries on through the season and the next`() =
        runTest {
            val run = run(Api(library), 1, 2)!!

            assertEquals(
                listOf("ep0101", "ep0102", "ep0103", "ep0201", "ep0202").map { "jellyfin:$it" },
                run.items.map { it.item.id.value },
            )
            assertEquals(QueueGroup("jellyfin:series:series00", "📺 Bluey"), run.group)
        }

    @Test
    fun `the series is found by one search when the Pi can search, and by walking the top level when it cannot`() =
        runTest {
            val searching = Api(library, searchable = true)
            val old = Api(library, searchable = false)

            run(searching, 1, 1)
            run(old, 1, 1)

            assertEquals(listOf("Bluey"), searching.searched)
            assertEquals(listOf<String?>("series00", "season01", "season02"), searching.browsed)
            assertEquals(listOf<String?>(null, "series00", "season01", "season02"), old.browsed)
        }

    @Test
    fun `a film has no series to carry on through`() = runTest {
        val film = JellyfinItem("film0000", "Heat", JellyfinKind.MOVIE).toPlayable(stream)

        assertNull(Api(library).seriesRunOf(film.item))
    }

    @Test
    fun `a series that is not in the library is unknown, so the episode plays alone`() = runTest {
        val stranger = episode("zz9999", 1, 1, series = "Peppa").toPlayable(stream)

        assertNull(Api(library).seriesRunOf(stranger.item))
    }

    @Test
    fun `an episode the series listing does not hold is not queued as part of it`() = runTest {
        assertNull(run(Api(library), 3, 7))
    }

    @Test
    fun `a film that shares the series name is not mistaken for the series`() = runTest {
        val film = JellyfinItem("film1111", "Bluey", JellyfinKind.MOVIE)
        val tricky = library + (null to (listOf(film) + library.getValue(null)))

        assertEquals(5, run(Api(tricky, searchable = true), 1, 1)!!.items.size)
    }
}
