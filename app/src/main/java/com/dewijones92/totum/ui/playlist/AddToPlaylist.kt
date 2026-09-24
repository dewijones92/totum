package com.dewijones92.totum.ui.playlist

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.ui.common.FilterableList
import kotlinx.coroutines.launch

/**
 * Returns a lambda to open an "add to playlist" picker for a [MediaItem], and
 * hosts the picker dialog. One reusable hook so any list row can offer it — the
 * item's pillar/handle is inferred from its media URL.
 */
@Composable
public fun rememberPlaylistAdder(container: AppContainer): (MediaItem) -> Unit {
    var target by remember { mutableStateOf<MediaItem?>(null) }
    target?.let { item ->
        AddToPlaylistDialog(container, item, onDismiss = { target = null })
    }
    return { target = it }
}

@Composable
private fun AddToPlaylistDialog(container: AppContainer, item: MediaItem, onDismiss: () -> Unit) {
    val store = container.localPlaylistStore
    val playlists by store.observePlaylists().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    val toAdd = remember(item) { item.toPlayableOrNull() }

    val addExisting: (PlaylistId) -> Unit = { id ->
        toAdd?.let { pi ->
            scope.launch { store.addItem(id, pi) }
            onDismiss()
        }
    }
    val createAndAdd: () -> Unit = {
        toAdd?.let { pi ->
            scope.launch { store.addItem(store.create(newName.trim()), pi) }
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = newName.isNotBlank() && toAdd != null, onClick = createAndAdd) {
                Text(stringResource(R.string.playlist_create_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        title = { Text(stringResource(R.string.playlist_add_to)) },
        text = { AddToPlaylistBody(playlists, newName, { newName = it }, addExisting) },
    )
}

@Composable
private fun AddToPlaylistBody(
    playlists: List<LocalPlaylist>,
    newName: String,
    onNewName: (String) -> Unit,
    onPick: (PlaylistId) -> Unit,
) {
    Column {
        if (playlists.isNotEmpty()) {
            FilterableList("add-to-playlist", playlists, { listOf(it.name) }, inset = 0.dp) { shown, _ ->
                LazyColumn(Modifier.heightIn(max = PICKER_MAX_HEIGHT)) {
                    items(shown, key = { it.id.value }) { playlist ->
                        Text(
                            text = playlist.name,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(playlist.id) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        OutlinedTextField(
            value = newName,
            onValueChange = onNewName,
            singleLine = true,
            label = { Text(stringResource(R.string.playlist_new_name)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val PICKER_MAX_HEIGHT = 240.dp
