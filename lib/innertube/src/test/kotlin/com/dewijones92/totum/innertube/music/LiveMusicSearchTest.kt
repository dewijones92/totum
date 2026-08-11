package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The real YouTube Music API, live.
 *
 * **Skipped unless `RUN_LIVE_MUSIC=1`.** It is the only thing that can catch YouTube changing the
 * renderers or the filter — which they do without notice, and which no fixture can notice — but it
 * needs the network and a residential address, so it cannot gate a commit. Everything it exercises
 * is covered deterministically by [MusicSearchParserTest] against a captured response.
 *
 * The query is deliberately a well-known recording that will still exist in ten years.
 */
class LiveMusicSearchTest {

    private val search = HttpYouTubeMusicSearch(InnerTubeClient(OkHttpClient()))

    @Test
    fun `it finds songs, with artists and durations`() = runTest {
        assumeLive()

        val result = search.searchSongs("nina simone feeling good", limit = 10)

        val songs = (result as? SearchSongsResult.Success)?.page?.items ?: error("search failed: $result")
        assertTrue("no songs came back", songs.isNotEmpty())
        // The filter is what earns this its place: unfiltered, most rows are videos with no album
        // and no artist. If these assertions start failing, the filter has stopped being honoured.
        assertTrue("no song named an artist", songs.any { it.artist != null })
        assertTrue("no song stated a duration", songs.all { it.durationSeconds != null })
    }

    @Test
    fun `every song it returns is playable by the ordinary video path`() = runTest {
        assumeLive()

        val result = search.searchSongs("nina simone feeling good", limit = 10)

        val songs = (result as SearchSongsResult.Success).page.items
        // A row with no watch URL would be a row that does nothing when tapped, which is the one
        // failure the parser exists to prevent.
        assertTrue(songs.all { it.watchUrl.value.contains("watch?v=") })
    }

    @Test
    fun `it finds the song, not just something adjacent to it`() = runTest {
        assumeLive()

        val result = search.searchSongs("nina simone feeling good", limit = 10)

        val titles = (result as SearchSongsResult.Success).page.items.map { it.title.lowercase() }
        assertTrue("nothing resembling the query in $titles", titles.any { it.contains("feeling good") })
    }

    private fun assumeLive() =
        assumeTrue("set RUN_LIVE_MUSIC=1 to run this", System.getenv("RUN_LIVE_MUSIC") == "1")
}
