package com.dewijones92.totum.data.audiobook

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.rss.RssParseResult
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import kotlin.time.Duration.Companion.seconds

class ArchiveBooksTest {

    private fun fixture(name: String) = javaClass.getResource("/archive/$name")!!.readText()

    @Test
    fun `each word must be in the title or the author, best-loved books first`() = runTest {
        var asked: HttpUrl? = null
        val source = ArchiveBookSearchSource { url ->
            if (asked == null) asked = url
            FetchResult.Success(fixture("search-persuasion.json"))
        }

        source.search(SearchQuery("Jane \"Austen\": persuasion!"), limit = 12, after = null)

        val url = URLDecoder.decode(asked!!.value, Charsets.UTF_8)
        assertTrue(url, url.startsWith("https://archive.org/advancedsearch.php?q="))
        assertTrue(
            url,
            url.contains(
                "(title:jane OR creator:jane) AND (title:austen OR creator:austen) AND " +
                    "(title:persuasion OR creator:persuasion) AND mediatype:audio AND collection:audio_bookspoetry"
            ),
        )
        assertTrue(url, url.contains("sort[]=downloads desc"))
        assertTrue(url, url.contains("rows=12"))
    }

    @Test
    fun `results become books that open as the item's chapters`() = runTest {
        val source = ArchiveBookSearchSource { FetchResult.Success(fixture("search-persuasion.json")) }

        val hits = (source.search(SearchQuery("persuasion"), limit = 10, after = null) as SearchOutcome.Success)
            .page.items.filterIsInstance<SearchHit.Book>()

        val first = hits.first()
        assertEquals("Persuasion", first.title)
        assertTrue(first.subtitle!!, first.subtitle!!.startsWith("Jane Austen · LibriVox · "))
        assertTrue(first.subtitle!!, first.subtitle!!.endsWith(" plays"))
        assertEquals("https://archive.org/metadata/${first.identifier}", first.feedUrl.value)
        assertEquals("https://archive.org/services/img/${first.identifier}", first.artworkUrl!!.value)
        val other = hits.last()
        assertEquals("Persuasion (a reading)", other.title)
        assertEquals("Jane Austen, A Reader · Internet Archive · 12 plays", other.subtitle)
        assertEquals(hits.size, hits.map { it.identifier }.toSet().size)
    }

    @Test
    fun `when the title and author search finds little, a looser search adds more`() = runTest {
        val asked = mutableListOf<String>()
        val strict = """{"response": {"docs": [{"identifier": "one", "title": "Dune talk", "downloads": 5}]}}"""
        val loose = """{"response": {"docs": [{"identifier": "one", "title": "Dune talk"},
            {"identifier": "two", "title": "Desert planet tales", "downloads": 9}]}}"""
        val source = ArchiveBookSearchSource { url ->
            asked += URLDecoder.decode(url.value, Charsets.UTF_8)
            FetchResult.Success(if (asked.size == 1) strict else loose)
        }

        val hits = (source.search(SearchQuery("dune"), limit = 40, after = null) as SearchOutcome.Success).page.items

        assertEquals(listOf("one", "two"), hits.filterIsInstance<SearchHit.Book>().map { it.identifier })
        assertEquals(2, asked.size)
        assertTrue(asked[1], !asked[1].contains("title:") && asked[1].contains("subject:audiobook"))
    }

    @Test
    fun `a query with no words asks nothing`() = runTest {
        var fetched = false
        val source = ArchiveBookSearchSource {
            fetched = true
            FetchResult.Success("{}")
        }

        val outcome = source.search(SearchQuery("?!"), limit = 10, after = null) as SearchOutcome.Success

        assertEquals(emptyList<SearchHit>(), outcome.page.items)
        assertEquals(false, fetched)
    }

    @Test
    fun `a network failure is a value`() = runTest {
        val source = ArchiveBookSearchSource { FetchResult.Failure("HTTP 503") }

        assertEquals(SearchOutcome.Failure("HTTP 503"), source.search(SearchQuery("dune"), 10, null))
    }

    @Test
    fun `a book's chapters come in order, in the smaller speech format, with their lengths`() {
        val parsed = ArchiveItemParser.parse(fixture("war_of_the_worlds_librivox.json")) as RssParseResult.Success

        val feed = parsed.feed
        assertEquals("The War of the Worlds", feed.title)
        assertEquals("H. G. Wells", feed.author)
        assertEquals("https://archive.org/services/img/war_of_the_worlds_librivox", feed.imageUrl)
        assertEquals("https://archive.org/details/war_of_the_worlds_librivox", feed.websiteUrl)
        assertEquals(
            listOf(
                "The War of the Worlds - Book 1 - Chapter 01",
                "The War of the Worlds - Book 1 - Chapter 02",
                "The War of the Worlds - Book 1 - Chapter 03",
            ),
            feed.episodes.map { it.title },
        )
        assertEquals(
            "https://archive.org/download/war_of_the_worlds_librivox/war_of_the_worlds_01_wells_64kb.mp3",
            feed.episodes.first().enclosureUrl,
        )
        assertEquals((13 * 60 + 52).seconds, feed.episodes.first().duration)
    }

    @Test
    fun `chapters with no track numbers anywhere play in natural name order`() {
        val body = """{"metadata": {"identifier": "x", "title": "X"}, "files": [
            {"name": "part 10.mp3", "format": "VBR MP3", "length": "61"},
            {"name": "part 2.mp3", "format": "VBR MP3", "length": "1:01:01"},
            {"name": "part 1.mp3", "format": "VBR MP3"}
        ]}"""

        val feed = (ArchiveItemParser.parse(body) as RssParseResult.Success).feed

        assertEquals(listOf("part 1", "part 2", "part 10"), feed.episodes.map { it.title })
        assertEquals("https://archive.org/download/x/part%201.mp3", feed.episodes.first().enclosureUrl)
        assertEquals((3600 + 61).seconds, feed.episodes[1].duration)
    }

    @Test
    fun `an item with no audio, or no item at all, is not a book`() {
        val noAudio = """{"metadata": {"identifier": "x", "title": "X"},
            "files": [{"name": "a.pdf", "format": "Text PDF"}]}"""

        assertTrue(ArchiveItemParser.parse(noAudio) is RssParseResult.Failure)
        assertTrue(ArchiveItemParser.parse("{}") is RssParseResult.Failure)
        assertTrue(ArchiveItemParser.parse("not json") is RssParseResult.Failure)
    }

    @Test
    fun `only an Internet Archive item address is a book`() {
        assertTrue(ArchiveBooks.isBook(ArchiveBooks.feedUrl("war_of_the_worlds_librivox")))
        assertEquals(false, ArchiveBooks.isBook(HttpUrl.of("https://feeds.example.com/archive.org/metadata/x")))
    }
}
