package com.dewijones92.totum.ui.playlist

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.ui.common.FilterableList
import com.dewijones92.totum.ui.common.MediaThumbnail

/** The signed-in account's playlists; tapping one opens its videos. */
@Composable
fun PlaylistsListScreen(
    container: AppContainer,
    onOpen: (Playlist) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: PlaylistsViewModel = viewModel(factory = PlaylistsViewModel.factory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    Surface(modifier = modifier.fillMaxSize()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.playlists_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            when (val s = state) {
                PlaylistsViewModel.UiState.Loading -> Centered { CircularProgressIndicator() }
                PlaylistsViewModel.UiState.SignedOut -> Centered { Text(stringResource(R.string.playlists_signed_out)) }
                PlaylistsViewModel.UiState.Error -> Centered { Text(stringResource(R.string.feed_error)) }
                is PlaylistsViewModel.UiState.Loaded ->
                    if (s.playlists.isEmpty()) {
                        Centered { Text(stringResource(R.string.playlists_empty)) }
                    } else {
                        FilterableList("account-playlists", s.playlists, { listOf(it.title) }) { shown, _ ->
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(shown, key = { it.browseId }) { playlist ->
                                    PlaylistRow(playlist, onClick = { onOpen(playlist) })
                                }
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        MediaThumbnail(
            url = playlist.thumbnailUrl?.let { HttpUrl.parse(it.value) },
            contentDescription = playlist.title,
            modifier = Modifier.size(width = 96.dp, height = 54.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = playlist.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            playlist.videoCountText?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(64.dp))
        content()
    }
}
