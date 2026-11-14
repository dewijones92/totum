package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import com.dewijones92.totum.innertube.music.fake.FakeYouTubeMusicCatalogue
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistLookupTest {

    private fun song(id: String, artist: String?) = MusicSong(
        videoId = id,
        title = "song $id",
        artist = artist,
        album = null,
        durationSeconds = 180,
        thumbnailUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
    )

    private fun album(id: String, artist: String?, vararg tracks: MusicSong) = MusicAlbum(
        browseId = id,
        title = "album $id",
        artist = artist,
        artistBrowseId = null,
        year = null,
        kind = MusicReleaseKind.ALBUM,
        summary = null,
        description = null,
        thumbnailUrl = null,
        playlistId = null,
        tracks = tracks.toList(),
    )

    private fun artistPage(id: String, name: String) = MusicArtist(
        browseId = id,
        name = name,
        description = null,
        thumbnailUrl = null,
        topSongs = emptyList(),
        allSongs = null,
        allAlbums = null,
        allSingles = null,
        albums = emptyList(),
        singles = emptyList(),
        similar = emptyList(),
        radio = null,
        shuffle = null,
    )

    private fun track(id: String, source: String = "music:other", album: AlbumRef? = null) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId(source),
            title = "song $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
            contentKind = MediaContentKind.MUSIC,
            album = album,
        ),
        PlayHandle.Podcast(),
    )

    private class Counting(private val inner: FakeYouTubeMusicCatalogue) : YouTubeMusicCatalogue by inner {
        val albumReads = mutableListOf<String>()
        val artistReads = mutableListOf<String>()

        override suspend fun album(browseId: String): MusicResult<MusicAlbum> {
            albumReads += browseId
            return inner.album(browseId)
        }

        override suspend fun artist(browseId: String): MusicResult<MusicArtist> {
            artistReads += browseId
            return inner.artist(browseId)
        }
    }

    private val fake = FakeYouTubeMusicCatalogue()
    private val catalogue = Counting(fake)
    private val lookup = ArtistLookup(catalogue)

    @Test
    fun `a track the album lists with its own artist takes that artist, not the album's`() = runTest {
        fake.albumsById = mapOf("A1" to album("A1", "Chopin", song("t1", "Pollini"), song("t2", null)))

        assertEquals("Pollini", lookup.of(track("t1", album = AlbumRef("A1", "album"))))
    }

    @Test
    fun `a listed track with no artist of its own takes the album artist`() = runTest {
        fake.albumsById = mapOf("A1" to album("A1", "Chopin", song("t2", null)))

        assertEquals("Chopin", lookup.of(track("t2", album = AlbumRef("A1", "album"))))
    }

    @Test
    fun `a track the album does not list takes the album artist`() = runTest {
        fake.albumsById = mapOf("A1" to album("A1", "Chopin", song("t1", "Pollini")))

        assertEquals("Chopin", lookup.of(track("elsewhere", album = AlbumRef("A1", "album"))))
    }

    @Test
    fun `when neither the track nor the album names an artist it learns nothing`() = runTest {
        fake.albumsById = mapOf("A1" to album("A1", null, song("t1", null)))

        assertNull(lookup.of(track("t1", album = AlbumRef("A1", "album"))))
    }

    @Test
    fun `the album comes from the source when the row has no album id`() = runTest {
        fake.albumsById = mapOf("A2" to album("A2", "Satie"))

        assertEquals("Satie", lookup.of(track("t9", source = "music:album:A2")))
        assertEquals("Satie", lookup.of(track("t9", source = "music:album:A2", album = AlbumRef(null, "album"))))
        assertEquals(listOf("A2"), catalogue.albumReads)
    }

    @Test
    fun `the row's own album id is preferred over the one in its source`() = runTest {
        fake.albumsById = mapOf(
            "own" to album("own", "From the row"),
            "A2" to album("A2", "From the source"),
        )

        assertEquals("From the row", lookup.of(track("t", source = "music:album:A2", album = AlbumRef("own", "x"))))
        assertEquals(listOf("own"), catalogue.albumReads)
    }

    @Test
    fun `many tracks of one album cost one read of it`() = runTest {
        fake.albumsById = mapOf("A1" to album("A1", "Chopin", song("t1", "One"), song("t2", "Two")))
        val ref = AlbumRef("A1", "album")

        assertEquals("One", lookup.of(track("t1", album = ref)))
        assertEquals("Two", lookup.of(track("t2", album = ref)))
        assertEquals("Chopin", lookup.of(track("t3", album = ref)))
        assertEquals(listOf("A1"), catalogue.albumReads)
    }

    @Test
    fun `two albums are each read once`() = runTest {
        fake.albumsById = mapOf("A1" to album("A1", "One"), "A2" to album("A2", "Two"))

        lookup.of(track("a", album = AlbumRef("A1", "x")))
        lookup.of(track("b", album = AlbumRef("A2", "y")))
        lookup.of(track("c", album = AlbumRef("A1", "x")))

        assertEquals(listOf("A1", "A2"), catalogue.albumReads)
    }

    @Test
    fun `an album that will not load learns nothing and is asked again next time`() = runTest {
        val ref = AlbumRef("A1", "album")

        assertNull(lookup.of(track("t1", album = ref)))
        fake.albumsById = mapOf("A1" to album("A1", "Chopin"))

        assertEquals("Chopin", lookup.of(track("t1", album = ref)))
        assertEquals(listOf("A1", "A1"), catalogue.albumReads)
    }

    @Test
    fun `a track from an artist page takes the artist's name, read once for the artist`() = runTest {
        fake.artistsById = mapOf("UC1" to artistPage("UC1", "Nina Simone"))

        assertEquals("Nina Simone", lookup.of(track("a", source = "music:artist:UC1")))
        assertEquals("Nina Simone", lookup.of(track("b", source = "music:artist:UC1")))
        assertEquals(listOf("UC1"), catalogue.artistReads)
    }

    @Test
    fun `an artist page that will not load learns nothing and is asked again next time`() = runTest {
        assertNull(lookup.of(track("a", source = "music:artist:UC1")))
        fake.artistsById = mapOf("UC1" to artistPage("UC1", "Nina Simone"))

        assertEquals("Nina Simone", lookup.of(track("a", source = "music:artist:UC1")))
        assertEquals(listOf("UC1", "UC1"), catalogue.artistReads)
    }

    @Test
    fun `a track with no album and no artist page to go to asks nobody`() = runTest {
        assertNull(lookup.of(track("t", source = "music:radio:x")))
        assertNull(lookup.of(track("t", source = "https://feed.test/rss")))

        assertEquals(emptyList<String>(), catalogue.albumReads)
        assertEquals(emptyList<String>(), catalogue.artistReads)
    }

    @Test
    fun `nearly six thousand tracks over three hundred albums cost three hundred reads`() = runTest {
        val albums = (1..ALBUMS).associate { a ->
            "A$a" to album("A$a", "Artist $a", *(1..TRACKS_PER_ALBUM).map { song("a${a}t$it", null) }.toTypedArray())
        }
        fake.albumsById = albums

        val artists = (1..ALBUMS).flatMap { a ->
            (1..TRACKS_PER_ALBUM).map { t -> lookup.of(track("a${a}t$t", album = AlbumRef("A$a", "x"))) }
        }

        assertEquals(ALBUMS * TRACKS_PER_ALBUM, artists.size)
        assertEquals((1..ALBUMS).map { "Artist $it" }, artists.chunked(TRACKS_PER_ALBUM).map { it.distinct().single() })
        assertEquals(ALBUMS, catalogue.albumReads.size)
        assertEquals(ALBUMS, catalogue.albumReads.toSet().size)
    }

    private companion object {
        const val ALBUMS = 300
        const val TRACKS_PER_ALBUM = 19
    }
}
