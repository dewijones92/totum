package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.search.SearchHit
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
        val fromSearch = SearchHit.Song("Sevillana", null, null, watch, 347, "Pablo Sainz Villegas", null)
            .toMediaItem(SourceId("search:ad-hoc-song"))
        val fromShare = requireNotNull(placeholderFor(watch, SourceId("shared")))

        assertEquals(fromShare.id, fromAlbum.id)
        assertEquals(fromShare.id, fromSearch.id)
    }
}
