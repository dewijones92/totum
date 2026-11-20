package com.dewijones92.totum.data.bbc

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.rss.RssParseResult
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.asPodcast
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class BbcProgrammesTest {

    private fun fixture(name: String) = checkNotNull(javaClass.getResource("/bbc/$name")).readText()

    private fun episodes() =
        (IblEpisodesParser.parse(fixture("hignfy-episodes.json")) as RssParseResult.Success).feed

    @Test
    fun `a programme's episodes become a feed named for the show and its channel, newest first`() {
        val feed = episodes()

        assertEquals("Have I Got News for You", feed.title)
        assertEquals("BBC One", feed.author)
        assertEquals("https://www.bbc.co.uk/iplayer/episodes/b006mkw3", feed.websiteUrl)
        assertEquals("https://ichef.bbci.co.uk/images/ic/640x360/p0n8hrph.jpg", feed.imageUrl)
        assertEquals(
            listOf("Series 72: Episode 6", "Series 72: Episode 5", "Series 70: Episode 9", "Series 66: Episode 2"),
            feed.episodes.map { it.title },
        )
    }

    @Test
    fun `an episode opens in iPlayer and says when it was shown, how long it is and when it expires`() {
        val first = episodes().episodes.first()

        assertEquals("bbc:m0033v6x", first.guid)
        assertEquals("https://www.bbc.co.uk/iplayer/episode/m0033v6x", first.enclosureUrl)
        assertEquals(Instant.parse("2026-11-06T21:25:00Z"), first.publishedAt)
        assertEquals(kotlin.time.Duration.parseIsoString("PT33M57.162666S"), first.duration)
        assertEquals(Instant.parse("2027-12-22T00:45:00Z"), first.availableUntil)
        assertEquals("https://ichef.bbci.co.uk/images/ic/640x360/p0plmvkr.jpg", first.imageUrl)
        assertTrue(first.description!!.startsWith("Steph McGovern hosts"))
    }

    @Test
    fun `an episode that expires soon carries its own end date`() {
        val expiring = episodes().episodes.single { it.guid == "bbc:m002n2bm" }

        assertEquals(Instant.parse("2026-12-04T21:00:00Z"), expiring.availableUntil)
    }

    @Test
    fun `a programme with nothing on iPlayer is a failure with a reason, not an empty show`() {
        val empty = """{"programme_episodes":{"page":1,"per_page":20,"count":0,"elements":[],"programme":null}}"""

        val result = IblEpisodesParser.parse(empty) as RssParseResult.Failure

        assertTrue(result.detail, result.detail.contains("no episodes"))
    }

    @Test
    fun `something that is not an iPlayer answer is a failure`() {
        assertTrue(IblEpisodesParser.parse("<html>nope</html>") is RssParseResult.Failure)
        assertTrue(IblEpisodesParser.parse("""{"other":1}""") is RssParseResult.Failure)
    }

    @Test
    fun `the feed address names the programme and only an iBL address is one`() {
        val url = BbcProgrammes.feedUrl("b006mkw3")

        assertEquals("https://ibl.api.bbci.co.uk/ibl/v1/programmes/b006mkw3/episodes?rights=web&per_page=50", url.value)
        assertTrue(BbcProgrammes.isProgramme(url))
        assertEquals("b006mkw3", BbcProgrammes.pidOf(url))
        assertEquals(false, BbcProgrammes.isProgramme(HttpUrl.of("https://example.com/feed.xml")))
        assertNull(BbcProgrammes.pidOf(HttpUrl.of("https://ibl.api.bbci.co.uk/ibl/v1/programmes/")))
    }

    @Test
    fun `search finds programmes and leaves episodes and clips out`() = runTest {
        var asked: HttpUrl? = null
        val source = BbcSearchSource { url ->
            asked = url
            FetchResult.Success(fixture("search-doctor.json"))
        }

        val hits = (source.search(SearchQuery("doctor who"), limit = 25, after = null) as SearchOutcome.Success)
            .page.items.filterIsInstance<SearchHit.Programme>()

        assertEquals(
            "https://ibl.api.bbci.co.uk/ibl/v1/new-search?q=doctor%20who&rights=web&per_page=25",
            asked!!.value,
        )
        assertEquals(listOf("Doctor Who (2005–2022)", "Doctor Who", "Doctor Foster"), hits.map { it.title })
        assertEquals("BBC One · 176 episodes · Sci-Fi", hits.first().subtitle)
        assertEquals(BbcProgrammes.feedUrl("b006q2x0"), hits.first().feedUrl)
        assertTrue(
            hits.first().artworkUrl!!.value,
            hits.first().artworkUrl!!.value.startsWith("https://ichef.bbci.co.uk/images/ic/640x360/")
        )
    }

    @Test
    fun `a programme hit is subscribed to through the podcast path, by its feed address`() = runTest {
        val source = BbcSearchSource { FetchResult.Success(fixture("search-doctor.json")) }
        val hit = (source.search(SearchQuery("doctor"), limit = 5, after = null) as SearchOutcome.Success)
            .page.items.filterIsInstance<SearchHit.Programme>().first()

        assertEquals(hit.feedUrl, hit.asPodcast().feedUrl)
        assertEquals(hit.title, hit.asPodcast().title)
    }

    @Test
    fun `a failed search says so and an unreadable answer is not a result`() = runTest {
        val failing = BbcSearchSource { FetchResult.Failure("HTTP 503", httpStatus = 503) }
        val garbled = BbcSearchSource { FetchResult.Success("not json") }

        assertEquals("HTTP 503", (failing.search(SearchQuery("x"), 5, null) as SearchOutcome.Failure).detail)
        assertTrue(garbled.search(SearchQuery("x"), 5, null) is SearchOutcome.Failure)
    }
}
