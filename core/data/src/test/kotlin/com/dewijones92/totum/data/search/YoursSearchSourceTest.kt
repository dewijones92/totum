package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomePiCall
import com.dewijones92.totum.data.home.JellyfinApi
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YoursSearchSourceTest {

    private fun playable(id: String, title: String, author: String? = null) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("test"),
            title = title,
            publishedAt = null,
            duration = null,
            author = author,
        ),
        PlayHandle.Podcast(),
    )

    private fun download(id: String, title: String) =
        DownloadedMedia(playable(id, title), localPath = "/data/$id.mkv", audioOnly = false)

    private fun job(id: String, title: String, state: HomeJobState = HomeJobState.DONE) =
        HomeJob(id, playable(id, title).toBackup(), state, audioOnly = false)

    private fun film(id: String, name: String, year: Int?) =
        JellyfinItem(id, name, JellyfinKind.MOVIE, year = year, container = "mkv")

    private fun episode(id: String, series: String, season: Int, number: Int, name: String) = JellyfinItem(
        id,
        name,
        JellyfinKind.EPISODE,
        seriesName = series,
        seasonNumber = season,
        episodeNumber = number,
        container = "mkv",
    )

    private class Jelly(private val answer: suspend (String) -> HomeAnswer<JellyfinListing>) : JellyfinApi {
        val asked = mutableListOf<String>()
        override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> =
            error("search does not browse")

        override suspend fun search(term: String): HomeAnswer<JellyfinListing> {
            asked += term
            return answer(term)
        }

        override fun streamUrl(item: JellyfinItem): HttpUrl = HttpUrl.of(
            "https://totum.example/fetch/jellyfin/stream/${item.id}.mkv"
        )
    }

    private fun answering(vararg items: JellyfinItem, searched: String? = "echo") = Jelly {
        HomeAnswer.Ok(JellyfinListing(null, "🔎", items.toList(), items.size, null, search = searched))
    }

    private fun source(
        phone: List<DownloadedMedia> = emptyList(),
        home: (suspend () -> HomeHoldings)? = null,
        jellyfin: JellyfinApi? = null,
        timeoutMs: Long = 10_000,
    ) = YoursSearchSource({ phone }, home, jellyfin, timeoutMs)

    private suspend fun YoursSearchSource.last(text: String): SearchOutcome.Success =
        updates(SearchQuery(text), LIMIT).toList().last() as SearchOutcome.Success

    private fun SearchOutcome.Success.rows(): List<SearchHit.Yours> = page.items.map { it as SearchHit.Yours }

    @Test
    fun `a film is found wherever it is, each row naming its place`() = runTest {
        val answer = source(
            phone = listOf(download("torrent:aaa:1", "Spinal Tap (1984) remastered"), download("x", "Heat")),
            home = { HomeHoldings.Read(listOf(job("torrent:bbb:1", "The Spinal Tap Story"), job("y", "Heat"))) },
            jellyfin = answering(film("0123456789abcdef", "This Is Spinal Tap Live", 1992)),
        ).last("spinal tap")

        val rows = answer.rows()
        assertEquals(
            mapOf(
                "Spinal Tap (1984) remastered" to setOf(YourPlace.PHONE),
                "The Spinal Tap Story" to setOf(YourPlace.HOME),
                "This Is Spinal Tap Live" to setOf(YourPlace.JELLYFIN),
            ),
            rows.associate { it.title to it.places },
        )
        assertTrue(answer.stillWaitingFor.isEmpty())
        assertTrue(answer.notes.isEmpty())
    }

    @Test
    fun `phone first, then home, then Jellyfin, each alphabetical`() = runTest {
        val answer = source(
            phone = listOf(download("p2", "Zulu show"), download("p1", "alpha show")),
            home = { HomeHoldings.Read(listOf(job("h2", "Zed show"), job("h1", "Able show"))) },
            jellyfin = answering(
                film("0123456789abcd02", "Zeta show", 2000),
                film("0123456789abcd01", "Beta show", 2001),
            ),
        ).last("show")

        assertEquals(
            listOf("alpha show", "Zulu show", "Able show", "Zed show", "Beta show", "Zeta show"),
            answer.rows().map { it.title },
        )
    }

    @Test
    fun `the same item on the phone and at home is one row in both places`() = runTest {
        val answer = source(
            phone = listOf(download("torrent:aaa:1", "Spinal Tap II")),
            home = { HomeHoldings.Read(listOf(job("torrent:aaa:1", "Spinal Tap II"))) },
        ).last("spinal")

        assertEquals(setOf(YourPlace.PHONE, YourPlace.HOME), answer.rows().single().places)
    }

    @Test
    fun `a film filed into Jellyfin from home is one row, the Jellyfin one, saying it is at home too`() = runTest {
        val answer = source(
            home = {
                HomeHoldings.Read(
                    listOf(job("torrent:aaa:1", "Spinal.Tap.II.The.End.Continues.2025.1080p.WEBRip.x265"))
                )
            },
            jellyfin = answering(film("0123456789abcdef", "Spinal Tap II: The End Continues", 2025)),
        ).last("spinal tap")

        val row = answer.rows().single()
        assertEquals("Spinal Tap II: The End Continues", row.title)
        assertEquals("jellyfin:0123456789abcdef", row.playable.item.id.value)
        assertEquals(setOf(YourPlace.HOME, YourPlace.JELLYFIN), row.places)
    }

    @Test
    fun `a Jellyfin film of another year is not taken for the home file with the same name`() = runTest {
        val answer = source(
            home = { HomeHoldings.Read(listOf(job("torrent:aaa:1", "Heat.1995.1080p"))) },
            jellyfin = answering(film("0123456789abcdef", "Heat", 2013)),
        ).last("heat")

        assertEquals(2, answer.rows().size)
    }

    @Test
    fun `a Jellyfin film with no year is never merged into anything`() = runTest {
        val answer = source(
            home = { HomeHoldings.Read(listOf(job("torrent:aaa:1", "Heat.1995.1080p"))) },
            jellyfin = answering(film("0123456789abcdef", "Heat", null)),
        ).last("heat")

        assertEquals(2, answer.rows().size)
    }

    @Test
    fun `a copy on the phone keeps its own row and gains Jellyfin, so it still plays offline`() = runTest {
        val answer = source(
            phone = listOf(download("torrent:aaa:1", "Spinal.Tap.II.2025.1080p")),
            jellyfin = answering(film("0123456789abcdef", "Spinal Tap II", 2025)),
        ).last("spinal")

        val row = answer.rows().single()
        assertEquals("torrent:aaa:1", row.playable.item.id.value)
        assertEquals(setOf(YourPlace.PHONE, YourPlace.JELLYFIN), row.places)
    }

    @Test
    fun `a Jellyfin film downloaded to the phone is one row, by its own id`() = runTest {
        val jelly = film("0123456789abcdef", "Heat", 1995)
        val answer = source(
            phone = listOf(download("jellyfin:0123456789abcdef", "Heat")),
            jellyfin = answering(jelly),
        ).last("heat")

        assertEquals(setOf(YourPlace.PHONE, YourPlace.JELLYFIN), answer.rows().single().places)
    }

    @Test
    fun `an episode in Jellyfin and its file at home are one row, matched by series and code`() = runTest {
        val answer = source(
            home = {
                HomeHoldings.Read(
                    listOf(
                        job("torrent:aaa:2", "Slow.Horses.S02E03.720p.WEB"),
                        job("torrent:aaa:3", "Slow.Horses.S02E04.720p")
                    ),
                )
            },
            jellyfin = answering(episode("0123456789abcd03", "Slow Horses", 2, 3, "Dead Cat")),
        ).last("horses")

        val rows = answer.rows()
        assertEquals(2, rows.size)
        assertEquals(
            setOf(YourPlace.HOME, YourPlace.JELLYFIN),
            rows.first {
                it.playable.item.id.value.startsWith("jellyfin:")
            }.places
        )
    }

    @Test
    fun `nothing matching is an empty answer with no note, not a failure`() = runTest {
        val answer = source(
            phone = listOf(download("x", "Heat")),
            home = { HomeHoldings.Read(listOf(job("y", "Heat"))) },
            jellyfin = answering(),
        ).last("spinal tap")

        assertTrue(answer.page.items.isEmpty())
        assertTrue(answer.notes.isEmpty())
        assertTrue(answer.stillWaitingFor.isEmpty())
    }

    @Test
    fun `a job that has not finished is not yours yet`() = runTest {
        val answer = source(
            home = {
                HomeHoldings.Read(
                    listOf(
                        job("y", "Spinal Tap", HomeJobState.DOWNLOADING),
                        job("z", "Spinal Tap 2", HomeJobState.FAILED)
                    )
                )
            },
        ).last("spinal")

        assertTrue(answer.page.items.isEmpty())
    }

    @Test
    fun `with the Pi unreachable the phone's matches still come, with the note`() = runTest {
        val answer = source(
            phone = listOf(download("torrent:aaa:1", "Spinal Tap")),
            home = { HomeHoldings.NotRead },
            jellyfin = Jelly { HomeAnswer.No("❌ The home server didn't answer") },
        ).last("spinal")

        assertEquals(listOf("Spinal Tap"), answer.rows().map { it.title })
        assertEquals(listOf(YoursSearchSource.HOME_UNREACHABLE), answer.notes)
        assertTrue(answer.stillWaitingFor.isEmpty())
    }

    @Test
    fun `the phone's matches arrive first, before the Pi has answered`() = runTest {
        val outcomes = source(
            phone = listOf(download("torrent:aaa:1", "Spinal Tap")),
            home = { awaitCancellation() },
            jellyfin = Jelly { awaitCancellation() },
            timeoutMs = 5_000,
        ).updates(SearchQuery("spinal"), LIMIT).toList().map { it as SearchOutcome.Success }

        val first = outcomes.first()
        assertEquals(listOf("Spinal Tap"), first.page.items.map { it.title })
        assertEquals(listOf(YoursSearchSource.HOME, YoursSearchSource.JELLYFIN), first.stillWaitingFor)
        assertTrue(first.notes.isEmpty())
        val last = outcomes.last()
        assertEquals(listOf("Spinal Tap"), last.page.items.map { it.title })
        assertEquals(listOf(YoursSearchSource.HOME_UNREACHABLE), last.notes)
        assertTrue(last.stillWaitingFor.isEmpty())
    }

    @Test
    fun `a Pi that answers without the search echo is an old Pi, said so, and nothing is listed from it`() = runTest {
        val answer = source(
            jellyfin = answering(film("0123456789abcdef", "Heat", 1995), searched = null),
        ).last("heat")

        assertTrue(answer.page.items.isEmpty())
        assertEquals(listOf(YoursSearchSource.JELLYFIN_NEEDS_UPDATING), answer.notes)
    }

    @Test
    fun `a Pi with no Jellyfin adds no note`() = runTest {
        val answer = source(
            phone = listOf(download("x", "Heat")),
            jellyfin = Jelly { HomeAnswer.No("ℹ️ Jellyfin isn't set up on the home server", 503) },
        ).last("heat")

        assertEquals(1, answer.page.items.size)
        assertTrue(answer.notes.isEmpty())
    }

    @Test
    fun `not signed in to the home server says so rather than that home is unreachable`() = runTest {
        val answer = source(jellyfin = Jelly { HomeAnswer.No(HomePiCall.NOT_SIGNED_IN) }).last("heat")

        assertEquals(listOf(HomePiCall.NOT_SIGNED_IN), answer.notes)
    }

    @Test
    fun `with no home server at all only the phone is searched and nothing waits`() = runTest {
        val outcomes = source(phone = listOf(download("x", "Heat"))).updates(SearchQuery("heat"), LIMIT).toList()

        assertEquals(1, outcomes.size)
        val only = outcomes.single() as SearchOutcome.Success
        assertEquals(1, only.page.items.size)
        assertTrue(only.stillWaitingFor.isEmpty())
        assertTrue(only.notes.isEmpty())
    }

    @Test
    fun `the Pi is asked with the query as typed`() = runTest {
        val jelly = answering()

        source(jellyfin = jelly).last("spinal tap")

        assertEquals(listOf("spinal tap"), jelly.asked)
    }

    @Test
    fun `at Dewi's scale a search over hundreds of downloads and home files is quick and capped`() = runTest {
        val many = (1..600).map { download("d$it", "Episode $it of a long running show") }
        val homeMany = (1..400).map { job("torrent:h:$it", "Show.S01E%02d.720p".format(it)) }
        val started = System.nanoTime()

        val answer = source(phone = many, home = { HomeHoldings.Read(homeMany) }).last("show")

        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(LIMIT, answer.page.items.size)
        assertTrue("took ${tookMs}ms", tookMs < 2_000)
    }

    private companion object {
        const val LIMIT = 200
    }
}
