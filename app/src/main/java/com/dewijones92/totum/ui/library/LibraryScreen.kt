package com.dewijones92.totum.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlaylistId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.domain.formatBytes
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.account.AccountScreen
import com.dewijones92.totum.ui.common.BuildInfoFooter
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.LocalItemActions
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.ScreenHeader
import com.dewijones92.totum.ui.common.SectionHeaderWithSortOptions
import com.dewijones92.totum.ui.common.SelectableMediaList
import com.dewijones92.totum.ui.common.TrackPlace
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.filterField
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rowCard
import com.dewijones92.totum.ui.history.PlayHistoryScreen
import com.dewijones92.totum.ui.playlist.LocalPlaylistDetailScreen
import com.dewijones92.totum.ui.playlist.LocalPlaylistsScreen
import com.dewijones92.totum.ui.playlist.rememberPlaylistAdder
import com.dewijones92.totum.ui.subscriptions.AllSubscriptionsScreen

@Composable
fun LibraryScreen(container: AppContainer, modifier: Modifier = Modifier) {
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showSubscriptions by rememberSaveable { mutableStateOf(false) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    // The id is a value class over a String, so it saves as one and needs no saver.
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    val playlist = openPlaylistId?.let(::PlaylistId)
    TrackPlace("library") {
        "playlists=$showPlaylists history=$showHistory subscriptions=$showSubscriptions account=$showAccount " +
            "playlist=$openPlaylistId"
    }

    val open = listOf(
        "playlist" to (playlist != null),
        "playlists" to showPlaylists,
        "history" to showHistory,
        "subscriptions" to showSubscriptions,
        "account" to showAccount,
    ).firstOrNull { it.second }?.first
    BackHandler(enabled = open != null) {
        Diag.log("nav", "back from library $open")
        when {
            playlist != null -> openPlaylistId = null
            showPlaylists -> showPlaylists = false
            showHistory -> showHistory = false
            showSubscriptions -> showSubscriptions = false
            else -> showAccount = false
        }
    }

    when {
        playlist != null ->
            LocalPlaylistDetailScreen(container, playlist, onBack = { openPlaylistId = null }, modifier = modifier)
        showPlaylists ->
            LocalPlaylistsScreen(
                container,
                onBack = { showPlaylists = false },
                onOpen = { openPlaylistId = it.value },
                modifier = modifier,
            )
        showHistory ->
            PlayHistoryScreen(container, onBack = { showHistory = false }, modifier = modifier)
        showSubscriptions ->
            AllSubscriptionsScreen(container, onBack = { showSubscriptions = false }, modifier = modifier)
        showAccount ->
            AccountScreen(container, modifier = modifier, onBack = { showAccount = false })
        else -> LibraryHome(
            container,
            onOpenPlaylists = { showPlaylists = true },
            onOpenHistory = { showHistory = true },
            onOpenSubscriptions = { showSubscriptions = true },
            onOpenAccount = { showAccount = true },
            modifier = modifier,
        )
    }
}

@Composable
private fun LibraryHome(
    container: AppContainer,
    onOpenPlaylists: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.factory(container))
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val inProgress by viewModel.inProgress.collectAsStateWithLifecycle()
    val failed by viewModel.failed.collectAsStateWithLifecycle()
    val storage by viewModel.storage.collectAsStateWithLifecycle()
    val sort by viewModel.sortOrder.collectAsStateWithLifecycle()
    val addToPlaylist = rememberPlaylistAdder(container)

    LibraryContent(
        downloaded = downloaded,
        inProgress = inProgress,
        failed = failed,
        onCancel = viewModel::cancel,
        onCancelAll = viewModel::cancelAll,
        onRetry = viewModel::retry,
        onDismiss = viewModel::dismiss,
        storage = storage,
        sort = sort,
        onOpenPlaylists = onOpenPlaylists,
        onOpenHistory = onOpenHistory,
        onOpenSubscriptions = onOpenSubscriptions,
        onOpenAccount = onOpenAccount,
        onPlay = viewModel::play,
        onDelete = viewModel::delete,
        onAddToPlaylist = { addToPlaylist(it.item) },
        onSetSort = viewModel::setSort,
        modifier = modifier,
    )
}

@Composable
internal fun LibraryContent(
    downloaded: List<LibraryViewModel.Entry>,
    /** Downloads running right now — shown above the finished ones, newest progress first. */
    inProgress: List<LibraryViewModel.InProgress>,
    failed: List<LibraryViewModel.Failed>,
    storage: StorageUsage,
    sort: DownloadSort,
    onOpenPlaylists: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenAccount: () -> Unit,
    onPlay: (LibraryViewModel.Entry) -> Unit,
    onDelete: (LibraryViewModel.Entry) -> Unit,
    onCancel: (MediaItemId) -> Unit,
    onCancelAll: () -> Unit,
    onRetry: (MediaItemId) -> Unit,
    onDismiss: (MediaItemId) -> Unit,
    onAddToPlaylist: (LibraryViewModel.Entry) -> Unit,
    onSetSort: (DownloadSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = LocalItemActions.current
    val listFilter = rememberListFilter("downloads")
    val shown = listFilter.filter(downloaded, { it.item.searchableText })
    Column(modifier = modifier.fillMaxSize()) {
        SelectableMediaList("downloads", downloaded, shown, { it.item }, Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { ScreenHeader(stringResource(R.string.destination_library)) }
                item {
                    LibraryTiles(
                        onOpenPlaylists = onOpenPlaylists,
                        onOpenSubscriptions = onOpenSubscriptions,
                        onOpenHistory = onOpenHistory,
                        onOpenAccount = onOpenAccount,
                    )
                }
                // In-progress FIRST, and outside the empty check: a fresh install with everything
                // still downloading would otherwise show "nothing downloaded yet" while the phone
                // was busily downloading, which is the most misleading thing this screen could say.
                runningSection(inProgress, onCancel, onCancelAll)
                failedSection(failed, onRetry, onDismiss)
                if (downloaded.isEmpty() && inProgress.isEmpty() && failed.isEmpty()) {
                    item { DownloadsEmpty() }
                } else if (downloaded.isNotEmpty()) {
                    item {
                        SectionHeaderWithSortOptions(
                            title = stringResource(R.string.library_downloads),
                            options = DownloadSort.ALL,
                            current = sort,
                            label = { it.labelRes },
                            onSelect = onSetSort,
                            extraActions = { FilterToggle(listFilter, downloaded.size) },
                        )
                    }
                    item { StorageSummary(storage) }
                    filterField(listFilter, shown.size, downloaded.size, hosted = true)
                    items(shown, key = { it.item.id.value }) { entry ->
                        MediaItemRow(
                            item = entry.item,
                            // The size sits with the item it belongs to; a total alone cannot
                            // tell you which download is the one worth deleting. Its own line, like
                            // every other fact — it used to be glued onto the end of the subtitle,
                            // which is precisely where an ellipsis reached it first.
                            subtitleLines = mediaItemFacts(entry.item, entry.media.pillar, LocalNow.current) +
                                "${FactEmoji.ON_DISK} ${formatBytes(entry.sizeBytes)}",
                            downloadState = DownloadState.Downloaded(entry.media.localPath, entry.media.audioOnly),
                            pillar = entry.media.pillar,
                            onPlay = { onPlay(entry) },
                            onDownload = { },
                            onDeleteDownload = { onDelete(entry) },
                            onAddToPlaylist = { onAddToPlaylist(entry) },
                            // An audio-only copy of a video is still missing the picture,
                            // and Library is exactly where you'd notice.
                            onDownloadVideo = actions?.let { { it.download(entry.item, audioOnly = false) } },
                        )
                    }
                }
            }
        }
        BuildInfoFooter()
    }
}

/**
 * What the downloads are costing, under the section heading. The queue downloads
 * everything in it automatically, so the app can fill a disk without being asked —
 * a number that only shows up once the phone complains has arrived too late.
 */
@Composable
private fun StorageSummary(storage: StorageUsage) {
    val used = formatBytes(storage.usedBytes)
    val text = storage.freeBytes
        ?.let { stringResource(R.string.library_storage_with_free, used, formatBytes(it)) }
        ?: stringResource(R.string.library_storage, used)
    Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        storage.freeBytes?.let { free ->
            val total = storage.usedBytes + free
            if (total > 0) {
                LinearProgressIndicator(
                    progress = { (storage.usedBytes.toFloat() / total).coerceIn(MIN_METER, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .height(6.dp)
                        .clip(CircleShape),
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
private fun LibraryTiles(
    onOpenPlaylists: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
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
    }
}

@Composable
private fun DownloadsEmpty() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Outlined.CollectionsBookmark,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.library_empty_headline),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = stringResource(R.string.library_empty_supporting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LibraryScreenPreview() {
    TotumTheme { LibraryScreen(FakeAppContainer()) }
}

/**
 * A download that stopped without finishing, with the reason and a way to act on it.
 *
 * These had nowhere to be shown: the Library listed finished and in-flight downloads, so a failure
 * disappeared from the UI entirely while its row sat in the database. Someone expecting an episode
 * on a plane would find no episode and no explanation.
 */
@Composable
private fun FailedRow(entry: LibraryViewModel.Failed, onRetry: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rowCard(
                MaterialTheme.colorScheme.errorContainer.copy(alpha = FAILED_CARD_ALPHA),
                MaterialTheme.shapes.large
            )
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            text = entry.item.title,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            // The reason verbatim. A generic "download failed" would hide the difference between
            // "members only", "no space" and "the home server was not there", which are the three
            // things worth telling apart.
            text = entry.reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.downloads_retry)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.playlist_delete)) }
        }
    }
}

/**
 * One download in flight: a bar you can watch, a percentage you can read, and a way to stop it.
 *
 * The bar and the percentage are deliberately BOTH. A bar alone cannot be read out or compared
 * between two rows, and a percentage alone gives no sense of movement — and the whole complaint
 * was that nothing on screen said anything was happening. Indeterminate when the server sends no
 * length, rather than a bar frozen at zero pretending to be stuck.
 */
@Composable
private fun DownloadingRow(active: LibraryViewModel.InProgress, onCancel: () -> Unit) {
    val fraction = active.state.fraction
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .rowCard(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                // The TITLE. This printed `active.id.value` — a raw media id like "chxbS3N3Llc" —
                // because the progress stream carried states without the items they were about.
                text = active.item.title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fraction
                    ?.let { stringResource(R.string.status_downloading_percent, (it * PERCENT).toInt()) }
                    ?: stringResource(R.string.status_downloading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.downloads_cancel),
                )
            }
        }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
    }
}

/** Fractions are 0..1; people read percentages. */
private const val PERCENT = 100
private const val MIN_METER = 0.02f
private const val FAILED_CARD_ALPHA = 0.35f
private val TILE_GAP = 12.dp
private val TILE_HEIGHT = 104.dp

/**
 * What is being fetched right now, with a way to stop it.
 *
 * Its own function because [LibraryContent] grew past what one function should hold once downloads
 * became manageable rather than merely visible — and because each section is now a separate idea.
 */
internal fun LazyListScope.runningSection(
    inProgress: List<LibraryViewModel.InProgress>,
    onCancel: (MediaItemId) -> Unit,
    onCancelAll: () -> Unit,
) {
    if (inProgress.isNotEmpty()) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.library_downloading_now,
                        inProgress.size,
                        inProgress.size,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                // Only offered when there is more than one, because with a single download
                // running it is the same action as the button on the row itself.
                if (inProgress.size > 1) {
                    TextButton(onClick = onCancelAll) {
                        Text(stringResource(R.string.downloads_cancel_all))
                    }
                }
            }
        }
        items(inProgress, key = { "downloading-${it.id.value}" }) { active ->
            DownloadingRow(active, onCancel = { onCancel(active.id) })
        }
    }
}

/** What did not finish, and why — see [FailedRow]. */
internal fun LazyListScope.failedSection(
    failed: List<LibraryViewModel.Failed>,
    onRetry: (MediaItemId) -> Unit,
    onDismiss: (MediaItemId) -> Unit,
) {
    if (failed.isNotEmpty()) {
        item {
            Text(
                text = stringResource(R.string.downloads_failed_section),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
            )
        }
        items(failed, key = { "failed-${it.id.value}" }) { entry ->
            FailedRow(entry, onRetry = { onRetry(entry.id) }, onDismiss = { onDismiss(entry.id) })
        }
    }
}
