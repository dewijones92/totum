package com.dewijones92.totum.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.ui.common.BuildInfoFooter
import com.dewijones92.totum.ui.common.ScreenHeader
import com.dewijones92.totum.ui.common.pinnedSection
import com.dewijones92.totum.ui.common.recentlySection

@Composable
internal fun LibraryHub(
    onOpenPlaylists: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    downloads: DownloadsSummary = DownloadsSummary(),
    phoneAlbums: PhoneAlbums = PhoneAlbums(),
) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f)) {
            item { ScreenHeader(stringResource(R.string.destination_library)) }
            pinnedSection()
            recentlySection()
            item {
                LibraryTiles(
                    onOpenPlaylists,
                    onOpenSubscriptions,
                    onOpenHistory,
                    onOpenAccount,
                    onOpenStats,
                    onOpenDownloads,
                    downloads,
                )
            }
            phoneAlbumsSection(phoneAlbums)
        }
        BuildInfoFooter()
    }
}

@Composable
private fun LibraryTiles(
    onOpenPlaylists: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenDownloads: () -> Unit,
    downloads: DownloadsSummary,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        LibraryTile(
            Icons.Outlined.BarChart,
            R.string.stats_tile,
            scheme.tertiary,
            scheme.onTertiary,
            onOpenStats,
            Modifier.fillMaxWidth(),
        )
        LibraryTile(
            Icons.Outlined.DownloadForOffline,
            R.string.library_downloads,
            scheme.surfaceContainerHigh,
            scheme.onSurface,
            onOpenDownloads,
            Modifier.fillMaxWidth().testTag(DOWNLOADS_TILE_TAG),
            detail = downloads.describe(),
        )
        CollectionRows(onOpenPlaylists, onOpenSubscriptions, onOpenHistory, onOpenAccount)
    }
}

@Composable
private fun CollectionRows(
    onOpenPlaylists: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            LibraryTile(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                R.string.playlists_title,
                scheme.secondaryContainer,
                scheme.onSecondaryContainer,
                onOpenPlaylists,
                Modifier.weight(1f),
            )
            LibraryTile(
                Icons.Outlined.Subscriptions,
                R.string.all_subscriptions_title,
                scheme.primaryContainer,
                scheme.onPrimaryContainer,
                onOpenSubscriptions,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            LibraryTile(
                Icons.Outlined.History,
                R.string.history_title,
                scheme.tertiaryContainer,
                scheme.onTertiaryContainer,
                onOpenHistory,
                Modifier.weight(1f),
            )
            // Account lives here rather than on the bottom bar: it's visited once to sign in,
            // so it doesn't earn a permanent tab (the queue does).
            LibraryTile(
                Icons.Outlined.AccountCircle,
                R.string.destination_account,
                scheme.surfaceContainerHighest,
                scheme.onSurface,
                onOpenAccount,
                Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun LibraryTile(
    icon: ImageVector,
    titleRes: Int,
    container: Color,
    content: Color,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .heightIn(min = TILE_HEIGHT)
            .clip(MaterialTheme.shapes.large)
            .background(container)
            .clickable(onClick = onOpen)
            .padding(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(28.dp))
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            color = content,
            modifier = Modifier.padding(top = 12.dp),
        )
        detail?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = content,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

internal const val DOWNLOADS_TILE_TAG = "library-downloads-tile"
private val TILE_GAP = 12.dp
private val TILE_HEIGHT = 104.dp
