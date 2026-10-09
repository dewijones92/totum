package com.dewijones92.totum.ui.search

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.map
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MediaItemActions
import com.dewijones92.totum.ui.common.MusicPage
import com.dewijones92.totum.ui.music.AlbumRow
import com.dewijones92.totum.ui.music.ArtistRow
import com.dewijones92.totum.ui.search.SearchViewModel.Results

internal fun LazyListScope.musicCollectionSections(results: Results.Loaded, onMore: (MusicShelf) -> Unit) {
    hitSection(
        { "${FactEmoji.ALBUM} " + stringResource(R.string.section_albums) },
        results.albums.map { it.items.take(results.shown(MusicShelf.ALBUMS)) },
    ) { hit ->
        val open = LocalOpenMusicPage.current
        AlbumRow(hit.ref, onOpen = {
            Diag.log("music", "search -> album ${hit.ref.browseId} \"${hit.ref.title}\"")
            open?.invoke(MusicPage.Album(hit.ref.browseId, title = hit.ref.title))
        })
    }
    moreMusic(results, MusicShelf.ALBUMS, R.string.search_more_albums, onMore)
    hitSection(
        { "${FactEmoji.ARTIST} " + stringResource(R.string.section_artists) },
        results.artists.map { it.items.take(results.shown(MusicShelf.ARTISTS)) },
    ) { hit ->
        val open = LocalOpenMusicPage.current
        ArtistRow(hit.ref.name, hit.ref.subtitle, hit.ref.thumbnailUrl, onOpen = {
            Diag.log("music", "search -> artist ${hit.ref.browseId} \"${hit.ref.name}\"")
            open?.invoke(MusicPage.Artist(hit.ref.browseId, hit.ref.name))
        })
    }
    moreMusic(results, MusicShelf.ARTISTS, R.string.search_more_artists, onMore)
}

internal fun LazyListScope.moreMusic(
    results: Results.Loaded,
    shelf: MusicShelf,
    labelRes: Int,
    onMore: (MusicShelf) -> Unit,
) {
    if (!results.hasMore(shelf)) return
    item(key = "more:" + shelf.name) {
        TextButton(
            onClick = { onMore(shelf) },
            enabled = !results.loadingMore,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text(stringResource(labelRes)) }
    }
}

internal fun LazyListScope.songSection(
    results: Results.Loaded,
    resolving: String?,
    onPlaySong: (SearchHit.Song) -> Unit,
    actions: MediaItemActions,
    onMore: (MusicShelf) -> Unit,
) {
    hitSection(
        { labelled(FactEmoji.SONG, R.string.section_songs) },
        results.songs.map { it.items.take(results.shown(MusicShelf.SONGS)) },
    ) { hit: SearchHit.Song ->
        SongHitRow(
            hit = hit,
            resolving = resolving == hit.watchUrl.value,
            onPlay = { onPlaySong(hit) },
            actions = actions,
        )
    }
    moreMusic(results, MusicShelf.SONGS, R.string.music_more_songs, onMore)
}
