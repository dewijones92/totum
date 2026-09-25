package com.dewijones92.totum.ui.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.BulkAction
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.FilterableList
import com.dewijones92.totum.ui.common.ListFilter
import com.dewijones92.totum.ui.common.SelectableList
import com.dewijones92.totum.ui.common.SelectionCheckbox
import com.dewijones92.totum.ui.common.isSelecting
import com.dewijones92.totum.ui.common.orSelected
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rememberSelection
import com.dewijones92.totum.ui.common.selectableClicks

/** The user's local playlists: create one, or open one. */
@Composable
fun LocalPlaylistsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onOpen: (PlaylistId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LocalPlaylistsViewModel = viewModel(factory = LocalPlaylistsViewModel.factory(container))
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val listFilter = rememberListFilter("local-playlists")

    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackHeader(stringResource(R.string.playlists_title), onBack) {
                FilterToggle(listFilter, playlists.size)
                IconButton(onClick = { creating = true }) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = stringResource(R.string.playlist_new),
                    )
                }
            }
            PlaylistList(playlists, listFilter, onOpen, onDelete = viewModel::delete)
        }
    }

    if (creating) {
        NamePlaylistDialog(
            title = stringResource(R.string.playlist_new),
            onConfirm = {
                viewModel.create(it)
                creating = false
            },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun PlaylistRow(playlist: LocalPlaylist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent.orSelected(playlist.id.value))
            .selectableClicks(playlist.id.value, enabled = true, onClick = onClick, onLongClick = null)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.PlaylistPlay,
            contentDescription = null,
            modifier = Modifier.padding(end = 16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = androidx.compose.ui.res.pluralStringResource(
                    R.plurals.playlist_item_count,
                    playlist.itemCount,
                    playlist.itemCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isSelecting()) SelectionCheckbox(playlist.id.value, playlist.name)
    }
}

/** A one-field name dialog, reused for create and rename. */
@Composable
internal fun NamePlaylistDialog(
    title: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.playlist_new_name)) },
            )
        },
    )
}

@Composable
private fun PlaylistList(
    playlists: List<LocalPlaylist>,
    listFilter: ListFilter,
    onOpen: (PlaylistId) -> Unit,
    onDelete: (PlaylistId) -> Unit,
) {
    val selection = rememberSelection("local-playlists")
    if (playlists.isEmpty()) {
        Text(
            text = stringResource(R.string.local_playlists_empty),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
        )
        return
    }
    FilterableList("local-playlists", playlists, { listOf(it.name) }, filter = listFilter) { shown, _ ->
        SelectableList(
            selection,
            playlists,
            shown,
            { it.id.value },
            { chosen ->
                listOf(
                    BulkAction(R.string.playlist_delete, confirm = R.plurals.playlist_delete_confirm) {
                        chosen.forEach { onDelete(it.id) }
                    },
                )
            },
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.id.value }) { playlist ->
                    PlaylistRow(playlist, onClick = { onOpen(playlist.id) })
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
