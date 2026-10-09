package com.dewijones92.totum.ui.search

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.music.AlbumRow
import com.dewijones92.totum.ui.music.ArtistRow
import com.dewijones92.totum.ui.search.SearchViewModel.Results

internal fun LazyListScope.musicCollectionSections(results: Results.Loaded) {
    hitSection({ "${FactEmoji.ALBUM} " + stringResource(R.string.section_albums) }, results.albums) { hit ->
        val open = LocalOpenMusicPage.current
        AlbumRow(hit.ref, onOpen = {
            Diag.log("music", "search -> album ${hit.ref.browseId} \"${hit.ref.title}\"")
            open?.invoke(MusicPage.Album(hit.ref.browseId, title = hit.ref.title))
        })
    }
    hitSection({ "${FactEmoji.ARTIST} " + stringResource(R.string.section_artists) }, results.artists) { hit ->
        val open = LocalOpenMusicPage.current
        ArtistRow(hit.ref.name, hit.ref.subtitle, hit.ref.thumbnailUrl, onOpen = {
            Diag.log("music", "search -> artist ${hit.ref.browseId} \"${hit.ref.name}\"")
            open?.invoke(MusicPage.Artist(hit.ref.browseId, hit.ref.name))
        })
    }
}
