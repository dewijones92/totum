package com.dewijones92.totum.innertube.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPageParserTest {

    private fun fixture(name: String): String = requireNotNull(
        javaClass.classLoader?.getResourceAsStream("music/$name"),
    ) { "music/$name is missing" }.bufferedReader().readText()

    @Test
    fun `album search reads each release with its artist, year and kind`() {
        val albums = MusicSearchParser.albums(fixture("albums-search.json"))

        assertEquals(4, albums.size)
        val first = albums.first()
        assertEquals("MPREb_tQfaWH32ovE", first.browseId)
        assertEquals("Abbey Road (Super Deluxe Edition)", first.title)
        assertEquals("The Beatles", first.artist)
        assertEquals("1969", first.year)
        assertEquals(MusicReleaseKind.ALBUM, first.kind)
        assertNotNull(first.thumbnailUrl)
    }

    @Test
    fun `a single in album search is a single`() {
        val single = MusicSearchParser.albums(fixture("albums-search.json")).first { it.title == "Abbey's Road" }

        assertEquals(MusicReleaseKind.SINGLE, single.kind)
        assertEquals("per'c", single.artist)
        assertEquals("2023", single.year)
    }

    @Test
    fun `album search rows are not mistaken for songs`() {
        assertTrue(MusicSearchParser.songs(fixture("albums-search.json")).isEmpty())
    }

    @Test
    fun `artist search reads the artist and drops the type word`() {
        val artists = MusicSearchParser.artists(fixture("artists-search.json"))

        val first = artists.first()
        assertEquals("UCJkuPtgOZnrjZkSb6JPEwoA", first.browseId)
        assertEquals("Pablo Sáinz-Villegas", first.name)
        assertEquals("69.5K monthly audience", first.subtitle)
    }

    @Test
    fun `an album page carries its header and its tracks`() {
        val album = requireNotNull(MusicPageParser.album(fixture("album.json"), "MPREb_ky8xEro8eK9"))

        assertEquals("Guitar Recital: Pablo Sainz Villegas", album.title)
        assertEquals("2004", album.year)
        assertEquals(MusicReleaseKind.ALBUM, album.kind)
        assertEquals("21 songs • 1 hour, 15 minutes", album.summary)
        assertEquals("UCJkuPtgOZnrjZkSb6JPEwoA", album.artistBrowseId)
        assertTrue(album.artist!!.startsWith("Pablo Sainz Villegas"))
        assertEquals("OLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw", album.playlistId)
        assertEquals("RDAMPLOLAK5uy_nMJmELuIq7hkr1fvMVh2JK50kfaoKr5yw", album.radio?.playlistId)
        assertEquals(4, album.tracks.size)
    }

    @Test
    fun `an album track takes its duration from the fixed column and the album's art`() {
        val album = requireNotNull(MusicPageParser.album(fixture("album.json"), "MPREb_ky8xEro8eK9"))
        val track = album.tracks.first()

        assertEquals("Nn_CcTBataE", track.videoId)
        assertEquals("Sevillana (Fantasia), Op. 29", track.title)
        assertEquals("Pablo Sainz Villegas & Joaquin Turina", track.artist)
        assertEquals(347L, track.durationSeconds)
        assertEquals(album.title, track.album)
        assertEquals(album.thumbnailUrl, track.thumbnailUrl)
    }

    @Test
    fun `an album with a description keeps it`() {
        val album = requireNotNull(MusicPageParser.album(fixture("album-with-description.json"), "MPREb_LQwx7Jq6u9H"))

        assertEquals("Abbey Road (2019 Mix)", album.title)
        assertEquals("The Beatles", album.artist)
        assertNotNull(album.description)
    }

    @Test
    fun `an album's playlist page names the album it belongs to`() {
        assertEquals("MPREb_ky8xEro8eK9", MusicPageParser.albumBrowseIdIn(fixture("album-as-playlist.json")))
    }

    @Test
    fun `an artist page carries top songs, releases, similar artists and its mixes`() {
        val artist = requireNotNull(MusicPageParser.artist(fixture("artist.json"), "UC2XdaAVUannpujzv32jcouQ"))

        assertEquals("The Beatles", artist.name)
        assertNotNull(artist.description)
        assertEquals(3, artist.topSongs.size)
        val top = artist.topSongs.first()
        assertEquals("Let It Be (Remastered 2009)", top.title)
        assertEquals("The Beatles", top.artist)
        assertEquals("478M plays", top.playsText)
        assertTrue(artist.allSongsBrowseId!!.startsWith("VLOLAK5uy_"))
        assertEquals("Rubber Soul (Super Deluxe)", artist.albums.first().title)
        assertEquals("2026", artist.albums.first().year)
        assertTrue(artist.singles.isNotEmpty())
        assertTrue(artist.singles.all { it.kind != MusicReleaseKind.ALBUM })
        assertEquals("John Lennon", artist.similar.first().name)
        assertTrue(artist.radio!!.playlistId.startsWith("RDEM"))
        assertTrue(artist.shuffle!!.playlistId.startsWith("RDAO"))
    }

    @Test
    fun `a radio batch reads each song and the continuation`() {
        val batch = MusicPageParser.radio(fixture("radio.json"))

        assertEquals(4, batch.songs.size)
        val seed = batch.songs.first()
        assertEquals("BNMKGYiJpvg", seed.videoId)
        assertEquals("Feeling Good", seed.title)
        assertEquals("Nina Simone", seed.artist)
        assertEquals("I Put A Spell On You", seed.album)
        assertEquals(174L, seed.durationSeconds)
        assertNotNull(batch.continuation)
    }

    @Test
    fun `a radio continuation reads the next batch and the next token`() {
        val batch = MusicPageParser.radio(fixture("radio-continuation.json"))

        assertEquals(4, batch.songs.size)
        assertNotNull(batch.continuation)
    }

    @Test
    fun `the library's saved albums are read from the TV tiles`() {
        val albums = MusicLibraryParser.libraryAlbums(fixture("library-albums-tv.json"))

        assertEquals(2, albums.size)
        val first = albums.first()
        assertEquals("MPREb_LQwx7Jq6u9H", first.browseId)
        assertEquals("Abbey Road (2019 Mix)", first.title)
        assertEquals("The Beatles", first.artist)
        assertEquals("1969", first.year)
    }

    @Test
    fun `the library's artists carry a name, art and their mix, but no channel id`() {
        val artists = MusicLibraryParser.libraryArtists(fixture("library-artists-tv.json"))

        assertEquals(listOf("The Beatles", "Nina Simone"), artists.map { it.name })
        assertEquals("RDEMhfZ0hwVu8nqhXF2NZYYzUA", artists.first().radio?.playlistId)
        assertNotNull(artists.first().thumbnailUrl)
    }

    @Test
    fun `library album tiles are not artists and artist tiles are not albums`() {
        assertTrue(MusicLibraryParser.libraryArtists(fixture("library-albums-tv.json")).isEmpty())
        assertTrue(MusicLibraryParser.libraryAlbums(fixture("library-artists-tv.json")).isEmpty())
    }

    @Test
    fun `garbage is not a page`() {
        assertNull(MusicPageParser.album("not json", "x"))
        assertNull(MusicPageParser.artist("{}", "x"))
        assertTrue(MusicPageParser.radio("{}").songs.isEmpty())
    }
}
