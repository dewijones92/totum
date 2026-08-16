package com.dewijones92.totum.ui.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.mediaItemFacts

/** One local playlist: Play all, play from an item, remove items, rename/delete. */
@Composable
fun LocalPlaylistDetailScreen(
    container: AppContainer,
    id: PlaylistId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LocalPlaylistDetailViewModel =
        viewModel(key = id.value, factory = LocalPlaylistDetailViewModel.factory(container, id))
    val name by viewModel.name.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }

    if (deleted) onBack()

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(
                name = name,
                onBack = onBack,
                onRename = { renaming = true },
                onDelete = viewModel::delete,
            )
            PlaylistBody(items, downloadStates, viewModel)
        }
    }

    if (renaming) {
        NamePlaylistDialog(
            title = stringResource(R.string.playlist_rename),
            initial = name,
            onConfirm = {
                viewModel.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun PlaylistBody(
    items: List<com.dewijones92.totum.domain.PlayableItem>,
    downloadStates: Map<com.dewijones92.totum.domain.MediaItemId, DownloadState>,
    viewModel: LocalPlaylistDetailViewModel,
) {
    if (items.isEmpty()) {
        Text(
            text = stringResource(R.string.playlist_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        )
        return
    }
    FilledTonalButton(
        onClick = viewModel::playAll,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null)
        Text(stringResource(R.string.playlist_play_all), modifier = Modifier.padding(start = 8.dp))
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.item.id.value }) { playlistItem ->
            val media = playlistItem.item
            MediaItemRow(
                item = media,
                subtitleLines = mediaItemFacts(media, playlistItem.handle.pillar),
                downloadState = downloadStates[media.id] ?: DownloadState.NotDownloaded,
                pillar = playlistItem.handle.pillar,
                onPlay = { viewModel.playFrom(playlistItem) },
                onDownload = { viewModel.download(media) },
                onDeleteDownload = { viewModel.deleteDownload(media.id) },
                onRemoveFromPlaylist = { viewModel.remove(media.id) },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun DetailHeader(name: String, onBack: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.queue_menu))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_rename)) },
                onClick = {
                    menu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_delete)) },
                onClick = {
                    menu = false
                    onDelete()
                },
            )
        }
    }
}
