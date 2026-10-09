package com.dewijones92.totum.innertube.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicListingParserTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("music/$name"),
    ) { "fixture $name missing" }.bufferedReader().use { it.readText() }

    @Test
    fun `an artist page says where all its songs and all its albums are`() {
        val artist = requireNotNull(MusicPageParser.artist(fixture("artist.json"), "UC2XdaAVUannpujzv32jcouQ"))

        assertEquals(MusicListing("VLOLAK5uy_mSSvmI1EpoPDI0BbUg1bPCOc6_pF8150Q", "ggMCCAI%3D"), artist.allSongs)
        assertEquals(MusicListing("MPADUC2XdaAVUannpujzv32jcouQ", "ggMIegYIARoCAQI%3D"), artist.allAlbums)
        assertNull("this artist's singles shelf offers no More", artist.allSingles)
    }

    @Test
    fun `all songs come a page at a time`() {
        val first = MusicListingParser.songs(fixture("artist-all-songs.json"))
        val next = MusicListingParser.songs(fixture("artist-all-songs-next.json"))

        assertEquals(
            listOf(
                "Let It Be (Remastered 2009)",
                "Here Comes The Sun (Remastered 2009)",
                "And I Love Her (Remastered 2009)",
            ),
            first.items.map { it.title },
        )
        assertEquals("Abbey Road (Remastered 2009)", first.items[1].album)
        assertNotNull("the first page names the next", first.next)
        assertEquals(2, next.items.size)
        assertNull("the last page names none", next.next)
        assertTrue(next.items.none { it.videoId in first.items.map { song -> song.videoId } })
    }

    @Test
    fun `a discography lists every release as an album to open`() {
        val releases = MusicListingParser.releases(fixture("artist-discography.json"), artist = "The Beatles")

        assertEquals(4, releases.size)
        assertEquals("Rubber Soul (Super Deluxe)", releases.first().title)
        assertEquals("2026", releases.first().year)
        assertTrue(releases.all { it.browseId.startsWith("MPREb_") })
        assertTrue(releases.all { it.kind == MusicReleaseKind.ALBUM })
    }
}
