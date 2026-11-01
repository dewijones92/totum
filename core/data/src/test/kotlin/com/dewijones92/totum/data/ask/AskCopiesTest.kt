package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.torrent.TorrentLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskCopiesTest {

    private val zog = Suggestion(AskKind.FILM, "Zog", 2018, "dragons", "Zog 2018")

    private fun torrent(title: String) =
        SearchHit.Torrent(title, null, null, TorrentLink.Magnet("magnet:?xt=urn:btih:$title"), 9, 1_000_000_000, "TPB")

    private fun source(vararg titles: String) = SearchSource { query, _, _ ->
        SearchOutcome.Success(Page.last(titles.map { torrent("$it [${query.value}]") }))
    }

    @Test
    fun `each kind searches its own sources with the suggested words`() = runTest {
        val asked = mutableListOf<AskKind>()
        val copies = AskCopies({ kind ->
            asked += kind
            listOf(source("a", "b"))
        }).find(zog)
        assertEquals(listOf(AskKind.FILM), asked)
        assertEquals(listOf("a [Zog 2018]", "b [Zog 2018]"), copies.map { it.title })
    }

    @Test
    fun `a radio searches each seed song and keeps the first copy of each`() = runTest {
        val radio = Suggestion(AskKind.RADIO, "Happy funk", null, "upbeat", "September EWF; Dean Town Vulfpeck")
        val copies = AskCopies({ listOf(source("first", "second")) }).find(radio)
        assertEquals(listOf("first [September EWF]", "first [Dean Town Vulfpeck]"), copies.map { it.title })
    }

    @Test
    fun `a tidied answer dates only films series and books, follows podcasts to their latest and drops links`() {
        val answer = AskAnswer(
            listOf(
                Suggestion(AskKind.ALBUM, "Bach Cello Suites", 1983, "calm", "Bach Cello Suites Yo-Yo Ma"),
                Suggestion(AskKind.FILM, "Apollo 11", 2019, "space", "Apollo 11 2019"),
                Suggestion(AskKind.PODCAST, "the rest is politics", 2020, "yours", "The Rest Is Politics Campbell"),
                Suggestion(AskKind.PODCAST, "In Our Time", 1998, "new to you", "In Our Time Bragg"),
            ),
            reply = "No new series yet ([GamesRadar](https://www.gamesradar.com/x)), so here are others.",
        ).tidied(Taste(shows = listOf("The Rest Is Politics")))
        assertEquals(listOf(null, 2019, null, null), answer.suggestions.map { it.year })
        assertEquals(AskKind.LATEST, answer.suggestions[2].kind)
        assertEquals("The Rest Is Politics", answer.suggestions[2].searchWords)
        assertEquals(AskKind.PODCAST, answer.suggestions[3].kind)
        assertEquals("No new series yet (GamesRadar), so here are others.", answer.reply)
    }

    @Test
    fun `a failing source leaves the others`() = runTest {
        val broken = SearchSource { _, _, _ -> SearchOutcome.Failure("down") }
        assertEquals(1, AskCopies({ listOf(broken, source("ok")) }).find(zog).size)
    }

    @Test
    fun `the best copy is the first the vet did not reject, in the app's own order`() {
        val card = AskCard(zog, listOf(torrent("Zog 2 2020"), torrent("Zog 2018 x265 10bit"), torrent("Zog 2018 x264")))
            .judged(
                VetAnswer(
                    listOf(
                        Verdict(0, 0, ok = false, reason = "the sequel"),
                        Verdict(0, 1, ok = true, reason = "right film"),
                        Verdict(1, 0, ok = false, reason = "another item"),
                    ),
                ),
                item = 0,
            )
        assertEquals("Zog 2018 x265 10bit", card.best?.title)
        assertEquals(listOf("Zog 2 2020" to "the sequel"), card.rejected.map { it.first.title to it.second })
    }

    @Test
    fun `everything rejected leaves no best copy, and no verdict counts as fine`() {
        val copies = listOf(torrent("Zog 2 2020"))
        assertNull(AskCard(zog, copies).judged(VetAnswer(listOf(Verdict(0, 0, false, "sequel"))), 0).best)
        assertEquals("Zog 2 2020", AskCard(zog, copies).best?.title)
    }

    @Test
    fun `the vet request names every copy with its size and seeders`() {
        val request = listOf(AskCard(zog, listOf(torrent("Zog 2018")))).vetRequest()
        assertEquals(CopyToVet("Zog 2018", 1_000_000_000, "9 seeders · TPB"), request.items.single().copies.single())
    }
}
