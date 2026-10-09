package com.dewijones92.totum.ui.music

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.OfflineCount
import com.dewijones92.totum.innertube.music.MusicAlbum
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.LocalOpenMusicPage
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.MusicPage

@Composable
fun MusicAlbumScreen(container: AppContainer, page: MusicPage.Album, onBack: () -> Unit) {
    val viewModel: MusicAlbumViewModel =
        viewModel(key = page.label, factory = MusicAlbumViewModel.factory(container, page))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = LocalItemActions.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            val kind = (state as? MusicAlbumViewModel.State.Loaded)?.album?.kind
            BackHeader(title = kind?.let { stringResource(it.labelRes) } ?: page.title, onBack = onBack)
        }
        when (val current = state) {
            MusicAlbumViewModel.State.Loading -> item { MusicLoading() }
            is MusicAlbumViewModel.State.Failed -> item { MusicFailed(viewModel::load) }
            is MusicAlbumViewModel.State.Loaded -> {
                item { AlbumHeader(current.album) }
                item { MusicButtons(albumButtons(current.album, viewModel, context, offline)) }
                item { OfflineLine(offline) }
                item { AboutText(current.album.description) }
                itemsIndexed(current.tracks, key = { _, item -> item.id.value }) { index, item ->
                    MediaItemRow(
                        item = item,
                        subtitleLines = listOfNotNull(
                            "${index + 1}. " + (item.author ?: current.album.artist.orEmpty()),
                        ),
                        pillar = MediaKind.VIDEO,
                        onPlay = { viewModel.play(fromIndex = index) },
                        onDownload = actions?.let { a -> { a.download(item, audioOnly = true) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumHeader(album: MusicAlbum) {
    val openPage = LocalOpenMusicPage.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MediaThumbnail(
            url = album.thumbnailUrl,
            contentDescription = album.title,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.size(ART_SIZE),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.titleLarge)
            album.artist?.let { artist ->
                val artistId = album.artistBrowseId
                Text(
                    text = "${FactEmoji.ARTIST} $artist",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.run { if (artistId != null) primary else onSurfaceVariant },
                    modifier = if (artistId != null && openPage != null) {
                        Modifier.clickable { openPage(MusicPage.Artist(artistId, artist)) }
                    } else {
                        Modifier
                    },
                )
            }
            Text(
                text = "${FactEmoji.ALBUM} " +
                    listOfNotNull(stringResource(album.kind.labelRes), album.year).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            album.summary?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun albumButtons(
    album: MusicAlbum,
    viewModel: MusicAlbumViewModel,
    context: Context,
    offline: OfflineCount,
): List<MusicButton> =
    listOfNotNull(
        MusicButton(R.string.music_play, Icons.Filled.PlayArrow, primary = true) {
            viewModel.play()
            toast(context, context.getString(R.string.music_album_queued, album.title))
        },
        MusicButton(R.string.music_shuffle, Icons.Filled.Shuffle) { viewModel.shuffle() },
        MusicButton(R.string.music_add_to_end, Icons.AutoMirrored.Filled.QueueMusic) {
            viewModel.addToEnd()
            toast(context, context.getString(R.string.music_album_added, album.title))
        },
        MusicButton(R.string.music_download_album, Icons.Outlined.Download) {
            val count = viewModel.download()
            toast(context, context.resources.getQuantityString(R.plurals.music_album_downloading, count, count))
        }.takeIf { !offline.complete },
        album.radio?.let {
            MusicButton(R.string.music_radio, Icons.Outlined.Radio) {
                viewModel.startRadio()
                toast(context, context.getString(R.string.music_radio_started, album.title))
            }
        },
    )

@Composable
private fun OfflineLine(offline: OfflineCount) {
    if (offline.total == 0 || (offline.downloaded == 0 && offline.inProgress == 0)) return
    val text = if (offline.complete) {
        stringResource(R.string.music_album_offline)
    } else {
        stringResource(R.string.music_album_offline_progress, offline.downloaded, offline.total, offline.inProgress)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (offline.complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

internal fun toast(context: Context, message: String) {
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
}

private val ART_SIZE = 132.dp
