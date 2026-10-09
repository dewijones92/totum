package com.dewijones92.totum.ui.music

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.innertube.music.MusicAlbumRef
import com.dewijones92.totum.innertube.music.MusicArtist
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.MusicPage

@Composable
fun MusicArtistScreen(container: AppContainer, page: MusicPage.Artist, onBack: () -> Unit) {
    val viewModel: MusicArtistViewModel =
        viewModel(key = page.label, factory = MusicArtistViewModel.factory(container, page))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = LocalItemActions.current
    LazyColumn(Modifier.fillMaxSize()) {
        item { BackHeader(title = stringResource(R.string.music_kind_artist), onBack = onBack) }
        when (val current = state) {
            MusicArtistViewModel.State.Loading -> item { MusicLoading() }
            is MusicArtistViewModel.State.Failed -> item { ArtistFailed(current, page, viewModel) }
            is MusicArtistViewModel.State.Loaded -> {
                val artist = current.artist
                item { ArtistHeader(artist) }
                item { MusicButtons(artistButtons(artist, viewModel, context)) }
                item { AboutText(artist.description) }
                if (current.topSongs.isNotEmpty()) {
                    item {
                        MusicSectionTitle("${FactEmoji.SONG} " + stringResource(R.string.section_top_songs))
                    }
                }
                itemsIndexed(current.topSongs, key = { _, item -> "top:" + item.id.value }) { index, item ->
                    MediaItemRow(
                        item = item,
                        subtitleLines = listOfNotNull(item.author?.let { "${FactEmoji.ARTIST} $it" }, item.viewsText),
                        pillar = MediaKind.VIDEO,
                        onPlay = { viewModel.playTop(fromIndex = index) },
                        onDownload = actions?.let { a -> { a.download(item, audioOnly = true) } },
                    )
                }
                releases("${FactEmoji.ALBUM} ", R.string.section_albums, artist.albums, "album")
                releases("${FactEmoji.ALBUM} ", R.string.section_singles, artist.singles, "single")
                similarArtists(artist)
            }
        }
    }
}

private fun LazyListScope.releases(emoji: String, titleRes: Int, albums: List<MusicAlbumRef>, kind: String) {
    if (albums.isEmpty()) return
    item { MusicSectionTitle(emoji + stringResource(titleRes)) }
    items(albums, key = { "$kind:" + it.browseId }) { album ->
        val open = LocalOpenMusicPage.current
        AlbumRow(album, onOpen = {
            Diag.log("music", "artist page -> $kind ${album.browseId} \"${album.title}\"")
            open?.invoke(MusicPage.Album(album.browseId, title = album.title))
        })
    }
}

private fun LazyListScope.similarArtists(artist: MusicArtist) {
    if (artist.similar.isEmpty()) return
    item { MusicSectionTitle("${FactEmoji.ARTIST} " + stringResource(R.string.section_similar_artists)) }
    items(artist.similar, key = { "similar:" + it.browseId }) { similar ->
        val open = LocalOpenMusicPage.current
        ArtistRow(similar.name, similar.subtitle, similar.thumbnailUrl, onOpen = {
            Diag.log("music", "artist ${artist.name} -> similar ${similar.browseId} \"${similar.name}\"")
            open?.invoke(MusicPage.Artist(similar.browseId, similar.name))
        })
    }
}

@Composable
private fun ArtistFailed(
    failed: MusicArtistViewModel.State.Failed,
    page: MusicPage.Artist,
    viewModel: MusicArtistViewModel
) {
    val context = LocalContext.current
    val started = stringResource(R.string.music_radio_started, page.name)
    MusicFailed(viewModel::load) {
        if (failed.canPlayMix) {
            TextButton(onClick = {
                viewModel.mix()
                toast(context, started)
            }) { Text(stringResource(R.string.music_mix)) }
        }
    }
}

private fun artistButtons(artist: MusicArtist, viewModel: MusicArtistViewModel, context: Context): List<MusicButton> =
    listOfNotNull(
        MusicButton(R.string.music_play, Icons.Filled.PlayArrow, primary = true) { viewModel.playTop() }
            .takeIf { artist.topSongs.isNotEmpty() },
        MusicButton(R.string.music_shuffle, Icons.Filled.Shuffle) {
            viewModel.shuffle()
            toast(context, context.getString(R.string.music_radio_started, artist.name))
        }.takeIf { artist.shuffle != null || artist.radio != null },
        MusicButton(R.string.music_mix, Icons.Outlined.Radio) {
            viewModel.mix()
            toast(context, context.getString(R.string.music_radio_started, artist.name))
        }.takeIf { artist.radio != null },
    )

@Composable
private fun ArtistHeader(artist: MusicArtist) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediaThumbnail(
            url = artist.thumbnailUrl,
            contentDescription = artist.name,
            shape = CircleShape,
            modifier = Modifier.size(ART_SIZE)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, style = MaterialTheme.typography.titleLarge)
        }
    }
}

private val ART_SIZE = 112.dp
