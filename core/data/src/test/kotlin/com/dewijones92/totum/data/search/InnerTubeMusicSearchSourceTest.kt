package com.dewijones92.totum.data.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.SearchSongsResult
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicSearch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Songs arriving through the one search seam.
 *
 * The mapping is thin, and the two things worth pinning are the two that a person sees: the
 * subtitle a row shows, and that a failure stays a failure rather than becoming an empty list.
 */
class InnerTubeMusicSearchSourceTest {

    @Test
    fun `a song becomes a Song hit with its artist and album`() = runTest {
        val source = InnerTubeMusicSearchSource(FakeYouTubeMusicSearch(song()))

        val hit = source.search(SearchQuery("nina"), limit = 10, after = null).hits().single()

        assertTrue(hit is SearchHit.Song)
        val song = hit as SearchHit.Song
        assertEquals("Feeling Good", song.title)
        assertEquals("Nina Simone", song.artist)
        assertEquals("I Put A Spell On You", song.album)
        assertEquals(174L, song.durationSeconds)
        assertEquals("276M plays", song.playsText)
    }

    @Test
    fun `the row reads artist then album, which is what tells two recordings apart`() = runTest {
        val source = InnerTubeMusicSearchSource(FakeYouTubeMusicSearch(song()))

        val hit = source.search(SearchQuery("nina"), 10, null).hits().single()

        assertEquals("Nina Simone • I Put A Spell On You", hit.subtitle)
    }

    @Test
    fun `an album nobody stated is simply left out of the subtitle`() = runTest {
        val source = InnerTubeMusicSearchSource(FakeYouTubeMusicSearch(song(album = null)))

        assertEquals("Nina Simone", source.search(SearchQuery("nina"), 10, null).hits().single().subtitle)
    }

    @Test
    fun `a song with neither artist nor album has no subtitle rather than an empty one`() = runTest {
        // An empty string would render as a blank second line, which reads as a broken row.
        val source = InnerTubeMusicSearchSource(FakeYouTubeMusicSearch(song(artist = null, album = null)))

        assertNull(source.search(SearchQuery("nina"), 10, null).hits().single().subtitle)
    }

    @Test
    fun `the watch url survives, because that is what plays it`() = runTest {
        val source = InnerTubeMusicSearchSource(FakeYouTubeMusicSearch(song()))

        val song = source.search(SearchQuery("nina"), 10, null).hits().single() as SearchHit.Song

        assertEquals(WATCH_URL, song.watchUrl.value)
    }

    @Test
    fun `a failure stays a failure`() = runTest {
        // Not an empty page: "YouTube Music said no" and "no songs match" are different, and the
        // section renders them differently on purpose.
        val source = InnerTubeMusicSearchSource(FakeYouTubeMusicSearch(SearchSongsResult.Failure("rejected")))

        val outcome = source.search(SearchQuery("nina"), 10, null)

        assertEquals(SearchOutcome.Failure("rejected"), outcome)
    }

    @Test
    fun `the query reaches the search unchanged`() = runTest {
        val search = FakeYouTubeMusicSearch(song())

        InnerTubeMusicSearchSource(search).search(SearchQuery("nina simone feeling good"), 10, null)

        assertEquals(listOf("nina simone feeling good"), search.queries)
    }

    @Test
    fun `the continuation is carried through, so the section can page`() = runTest {
        val next = PageToken("more")
        val search = FakeYouTubeMusicSearch(SearchSongsResult.Success(Page(listOf(song()), next)))

        val outcome = search.let(::InnerTubeMusicSearchSource).search(SearchQuery("nina"), 10, null)

        assertEquals(next, (outcome as SearchOutcome.Success).page.next)
    }

    private fun SearchOutcome.hits(): List<SearchHit> = (this as SearchOutcome.Success).page.items

    private fun song(
        artist: String? = "Nina Simone",
        album: String? = "I Put A Spell On You",
    ) = MusicSong(
        videoId = "BNMKGYiJpvg",
        title = "Feeling Good",
        artist = artist,
        album = album,
        durationSeconds = 174,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of(WATCH_URL),
        playsText = "276M plays",
    )

    private companion object {
        const val WATCH_URL = "https://www.youtube.com/watch?v=BNMKGYiJpvg"
    }
}
