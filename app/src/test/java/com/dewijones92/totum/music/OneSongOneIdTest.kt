package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.music.MusicSong
import com.dewijones92.totum.placeholderFor
import com.dewijones92.totum.ui.common.toMediaItem
import org.junit.Assert.assertEquals
import org.junit.Test

class OneSongOneIdTest {

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=Nn_CcTBataE")

    @Test
    fun `a song from an album, from search and from a shared link is one item`() {
        val fromAlbum = MusicSong("Nn_CcTBataE", "Sevillana", "Pablo Sainz Villegas", null, 347, null, watch)
            .toMediaItem(MusicSources.album("MPREb_ky8xEro8eK9"))
        val fromSearch = SearchHit.Song(
            MusicSong("Nn_CcTBataE", "Sevillana", "Pablo Sainz Villegas", null, 347, null, watch)
        )
            .toMediaItem(SourceId("search:ad-hoc-song"))
        val fromShare = requireNotNull(placeholderFor(watch, SourceId("shared")))

        assertEquals(fromShare.id, fromAlbum.id)
        assertEquals(fromShare.id, fromSearch.id)
    }

    @Test
    fun `a video from search is the same item as the shared link`() {
        val fromSearch = SearchHit.Video("Sevillana (live)", null, null, watch, 347)
            .toMediaItem(SourceId("search:ad-hoc-video"))

        assertEquals(requireNotNull(placeholderFor(watch, SourceId("shared"))).id, fromSearch.id)
    }

    @Test
    fun `a song knows its artist's channel and its album, so the player can go to either`() {
        val song = MusicSong(
            "Nn_CcTBataE", "Sevillana", "Pablo Sainz Villegas", "Soñando España", 347, null, watch,
            artistId = "UCqj1HtTq76Bo6rBXNwrF5Uw", albumId = "MPREb_ky8xEro8eK9",
        ).toMediaItem(MusicSources.radio("RDAMVMx"))

        assertEquals("https://www.youtube.com/channel/UCqj1HtTq76Bo6rBXNwrF5Uw", song.sourceUrl?.value)
        assertEquals(AlbumRef("MPREb_ky8xEro8eK9", "Soñando España"), song.album)
    }
}
