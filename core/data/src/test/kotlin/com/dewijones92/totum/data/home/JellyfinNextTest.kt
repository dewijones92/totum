package com.dewijones92.totum.data.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.domain.EpisodeCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JellyfinNextTest {

    private fun episode(id: String, season: Int, number: Int) = JellyfinItem(
        id = id,
        name = "Episode $number",
        kind = JellyfinKind.EPISODE,
        seriesName = "Bluey",
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

    private val api = object : JellyfinApi {
        override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> {
            val items = library[parent] ?: return HomeAnswer.No("no such folder")
            return HomeAnswer.Ok(JellyfinListing(parent, "listing", items))
        }

        override fun streamUrl(item: JellyfinItem): HttpUrl =
            HttpUrl.of("https://pi.test/jellyfin/stream/${item.id}.mkv")
    }

    private suspend fun after(season: Int, number: Int) =
        api.episodesAfter("Bluey", EpisodeCode(season, number))?.map { it.item.id.value }

    @Test
    fun `the rest of the season follows an episode in order`() = runTest {
        assertEquals(listOf("jellyfin:ep0102", "jellyfin:ep0103"), after(1, 1))
    }

    @Test
    fun `the last episode of a season is followed by the next season`() = runTest {
        assertEquals(listOf("jellyfin:ep0201", "jellyfin:ep0202"), after(1, 3))
    }

    @Test
    fun `the last episode of the series has nothing after it`() = runTest {
        assertEquals(emptyList<String>(), after(2, 2))
    }

    @Test
    fun `a series that is not in the library is unknown, not empty`() = runTest {
        assertNull(api.episodesAfter("Peppa", EpisodeCode(1, 1)))
    }
}
