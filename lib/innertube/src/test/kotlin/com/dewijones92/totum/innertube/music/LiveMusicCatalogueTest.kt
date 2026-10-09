package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.innertube.browse.InnerTubeClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveMusicCatalogueTest {

    private val catalogue = HttpYouTubeMusicCatalogue(InnerTubeClient(OkHttpClient()))

    @Test
    fun `album search finds the album and its page lists its tracks`() = runTest {
        assumeLive()

        val albums = catalogue.albums("abbey road beatles", limit = 5).value().items
        val first = albums.first()
        val album = catalogue.album(first.browseId).value()

        assertTrue("no tracks on ${first.title}", album.tracks.size > 5)
        assertNotNull(album.playlistId)
        assertTrue(album.tracks.all { it.durationSeconds != null })
    }

    @Test
    fun `an album playlist link resolves to the same album`() = runTest {
        assumeLive()

        val album = catalogue.albumForPlaylist(GUITAR_RECITAL_PLAYLIST).value()

        assertEquals("MPREb_ky8xEro8eK9", album.browseId)
        assertEquals(21, album.tracks.size)
    }

    @Test
    fun `artist search and the artist page`() = runTest {
        assumeLive()

        val artistRef = catalogue.artists("the beatles", limit = 3).value().items.first()
        val artist = catalogue.artist(artistRef.browseId).value()

        assertTrue(artist.topSongs.isNotEmpty())
        assertTrue(artist.albums.isNotEmpty())
        assertNotNull(artist.radio)
    }

    @Test
    fun `a radio continues with new songs`() = runTest {
        assumeLive()

        val seed = RadioSeed.forSong("BNMKGYiJpvg")
        val first = catalogue.radio(seed).value()
        val second = catalogue.radio(seed, first.continuation).value()

        assertTrue(first.songs.size >= 20)
        val fresh = second.songs.map { it.videoId } - first.songs.map { it.videoId }.toSet()
        assertTrue("the continuation added nothing new", fresh.isNotEmpty())
    }

    private fun <T> MusicResult<T>.value(): T = when (this) {
        is MusicResult.Success -> value
        is MusicResult.Failure -> error("live call failed: $detail")
    }

    private fun assumeLive() =
        assumeTrue("set RUN_LIVE_MUSIC=1 to run this", System.getenv("RUN_LIVE_MUSIC") == "1")

    private companion object {
        const val GUITAR_RECITAL_PLAYLIST = "OLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw"
    }
}
