package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.MusicReleaseKind
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.MusicSong
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WalkDiscographyTest {

    private fun song(id: String) =
        MusicSong(id, "song $id", "Led Zeppelin", null, 200, null, HttpUrl.of("https://www.youtube.com/watch?v=$id"))

    private fun ref(id: String, kind: MusicReleaseKind = MusicReleaseKind.ALBUM) =
        MusicAlbumRef(id, "release $id", "Led Zeppelin", null, kind, null)

    private fun album(id: String, vararg tracks: String) = MusicAlbum(
        id, "release $id", "Led Zeppelin", null, null, MusicReleaseKind.ALBUM, null, null, null, null,
        tracks.map(::song),
    )

    private val albumsListing = MusicListing("albums", "p")

    private val artist = MusicArtist(
        browseId = "UC-zep",
        name = "Led Zeppelin",
        description = null,
        thumbnailUrl = null,
        topSongs = emptyList(),
        allSongs = MusicListing("VLtop", null),
        allAlbums = albumsListing,
        allSingles = null,
        albums = listOf(ref("IV")),
        singles = listOf(ref("single", MusicReleaseKind.SINGLE)),
        similar = emptyList(),
        radio = null,
        shuffle = null,
    )

    @Test
    fun `every album from the see-all list and every single on the page, each song once, failures named`() = runTest {
        val walk = walkDiscography(
            artist,
            releases = { listing ->
                if (listing == albumsListing) {
                    MusicResult.Success(
                        listOf(ref("I"), ref("IV"), ref("broken"))
                    )
                } else {
                    MusicResult.Failure("no")
                }
            },
            album = { id ->
                when (id) {
                    "I" -> MusicResult.Success(album("I", "good-times", "dazed"))
                    "IV" -> MusicResult.Success(album("IV", "stairway", "dazed"))
                    "single" -> MusicResult.Success(album("single", "whole-lotta"))
                    else -> MusicResult.Failure("gone")
                }
            },
        )

        assertEquals(listOf("good-times", "dazed", "stairway", "whole-lotta"), walk.songs.map { it.videoId })
        assertEquals(4, walk.releases)
        assertEquals(listOf("release broken: gone"), walk.failed)
    }
}
