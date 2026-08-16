package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.OfflineReadiness
import com.dewijones92.totum.domain.unavailableOfflineNow
import com.dewijones92.totum.ui.common.CollapsingTitle
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.EqualiserSize
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.PlayingEqualiser
import com.dewijones92.totum.ui.common.ReorderState
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberReorderState
import com.dewijones92.totum.ui.common.reorderable
import kotlinx.coroutines.launch

/**
 * The queue: what is playing now and what follows, for both pillars at once.
 *
 * Entries that arrived together (a "Play all") share a [com.dewijones92.totum.data.queue.QueueGroup]
 * tag, and a header is drawn over each **contiguous run** of the same tag with a
 * one-tap "remove these". Because grouping is drawn from runs rather than stored as
 * structure, dragging an entry out simply splits the run — nothing to repair.
 */
@Composable
fun QueueScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val queue = container.playbackQueue
    val snapshot by queue.state.collectAsStateWithLifecycle()
    val downloads by container.downloadManager.observeDownloads().collectAsStateWithLifecycle(emptyMap())
    val playing by container.playbackController.state.collectAsStateWithLifecycle()
    val entries = snapshot.entries
    val scope = rememberCoroutineScope()

    // Hoisted so the header can collapse against it — the header sits outside the list, so
    // it cannot read a state the list owns privately.
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        QueueHeader(canClear = entries.isNotEmpty(), onClear = queue::clear, listState = listState)
        if (entries.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.QueueMusic,
                headline = stringResource(R.string.queue_title),
                supportingText = stringResource(R.string.queue_empty),
            )
        } else {
            val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
            OfflineSummary(
                readiness = readinessOf(entries, downloads),
                autoDownloadOff = !settings.autoDownloadQueue,
                waitingForWifi = settings.autoDownloadQueue && !container.autoDownloadAllowedNow(),
            )
            // Survives rotation and process death: a collapsed 24-episode season staying
            // collapsed is the entire point, and re-expanding on every return would undo it.
            var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }
            val reorder = rememberReorderState(listState = listState, onMove = queue::move)
            LazyColumn(
                state = listState,
                // The container has to be known for a drag held at an edge to scroll the list;
                // without it dragging still works, it just cannot reach past the screen.
                modifier = with(reorder) { Modifier.fillMaxSize().reorderContainer() },
            ) {
                itemsWithGroupHeaders(
                    availability = QueueAvailability(downloads, container.isOffline()),
                    entries = entries,
                    nowPlaying = NowPlaying(snapshot.currentIndex, playing?.progress, playing?.isPlaying == true),
                    reorder = reorder,

                    actions = QueueActions(
                        onPlay = queue::jumpTo,
                        onRemove = queue::removeAt,
                        onRemoveGroup = queue::removeGroup,
                        onMove = queue::move,
                        // A manual retry: the queue fetches audio by itself, but a failed
                        // or skipped fetch otherwise leaves no way to ask again.
                        onDownload = { item ->
                            scope.launch { container.downloadManager.download(item, audioOnly = true) }
                        },
                        onDownloadVideo = { item ->
                            scope.launch { container.downloadManager.download(item, audioOnly = false) }
                        },
                        groups = GroupCollapse(
                            collapsedIds = collapsedGroups,
                            onChange = { collapsedGroups = it },
                        ),
                        onDeleteDownload = { id -> scope.launch { container.downloadManager.delete(id) } },
                    ),
                )
            }
        }
    }
}

/** What a queue row can do — bundled so the row builder isn't a wall of lambdas. */
private data class QueueActions(
    val onPlay: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
    val onRemoveGroup: (String) -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onDownload: (MediaItem) -> Unit,
    val onDownloadVideo: (MediaItem) -> Unit,
    val onDeleteDownload: (MediaItemId) -> Unit,
    /** Which group runs are folded away. Here because it travels with the rows, like the rest. */
    val groups: GroupCollapse,
)

/**
 * Which group runs are folded away, and how to fold another.
 *
 * One type rather than a set plus a lambda: they are meaningless apart, and passing them
 * separately pushed the row emitter past its parameter limit — which is the limit doing its job.
 */
private data class GroupCollapse(
    private val collapsedIds: Set<String>,
    private val onChange: (Set<String>) -> Unit,
) {
    fun isCollapsed(id: String): Boolean = id in collapsedIds

    fun toggle(id: String) {
        onChange(if (id in collapsedIds) collapsedIds - id else collapsedIds + id)
    }
}

/** Where the cursor is and how far through that item playback has got. */
private data class NowPlaying(val index: Int, val progress: Float?, val isPlaying: Boolean)

/**
 * What a queue row needs to know about whether it can actually be played.
 *
 * The two travel together everywhere — a download state means something different with no network
 * behind it — so they are one parameter rather than two that could be passed inconsistently.
 *
 * [offline] is read once per list composition rather than observed: it changes a row's WORDING, not
 * its behaviour, and the queue recomposes whenever a download state does.
 */
private data class QueueAvailability(
    val downloads: Map<MediaItemId, DownloadState>,
    val offline: Boolean,
) {
    fun stateOf(id: MediaItemId): DownloadState = downloads[id] ?: DownloadState.NotDownloaded
}

/**
 * Emits the queue rows, inserting a header wherever the group tag changes — so a
 * run of entries from one "Play all" reads as a block and can be dropped together.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.itemsWithGroupHeaders(
    entries: List<QueueEntry>,
    nowPlaying: NowPlaying,
    availability: QueueAvailability,
    reorder: ReorderState,
    actions: QueueActions,
) {
    val groups = actions.groups
    entries.forEachIndexed { index, entry ->
        val group = entry.group
        val startsRun = group != null && entries.getOrNull(index - 1)?.group?.id != group.id
        // A group need not be contiguous — queueing something else mid-season splits it — so each
        // RUN normally draws its own header. Collapsed, all of them are hidden together, and
        // repeating "3 items hidden" once per run would be three lies about the same three items.
        // So a collapsed group shows one header: the first run's.
        val isFirstRunOfGroup = group != null && entries.take(index).none { it.group?.id == group.id }
        val alreadyCollapsedAbove = group != null && groups.isCollapsed(group.id) && !isFirstRunOfGroup
        if (startsRun && !alreadyCollapsedAbove) {
            val run = entries.filter { it.group?.id == group.id }
            item(key = "group-$index-${group.id}") {
                GroupHeader(
                    groupId = group.id,
                    isFirstRun = isFirstRunOfGroup,
                    title = group.title,
                    count = run.size,
                    collapsed = groups.isCollapsed(group.id),
                    containsNowPlaying = entries.getOrNull(nowPlaying.index)?.group?.id == group.id,
                    onToggle = { groups.toggle(group.id) },
                    onRemoveGroup = { actions.onRemoveGroup(group.id) },
                )
            }
        }
        // Hidden, not removed: the entry keeps its index, so reordering and "play this" still
        // address the same item once it is shown again.
        if (group != null && groups.isCollapsed(group.id)) return@forEachIndexed
        item(key = entry.item.item.id.value) {
            val media = entry.item.item
            if (index == nowPlaying.index) NowPlayingLabel(nowPlaying.progress, nowPlaying.isPlaying)
            val downloadState = availability.stateOf(media.id)
            MediaItemRow(
                item = media,
                // Says why a row will be passed over, rather than leaving it to be discovered.
                // It REPLACES the facts rather than joining them: "this will be skipped" is the
                // only thing worth reading on a row you cannot play.
                subtitleLines = if (unavailableOfflineNow(downloadState, availability.offline)) {
                    listOf("${FactEmoji.UNAVAILABLE} ${stringResource(R.string.queue_unavailable_offline)}")
                } else {
                    mediaItemFacts(media, entry.item.handle.pillar)
                },
                downloadState = downloadState,
                pillar = entry.item.handle.pillar,
                onPlay = { actions.onPlay(index) },
                onDownload = { actions.onDownload(media) },
                onDeleteDownload = { actions.onDeleteDownload(media.id) },
                onDownloadVideo = { actions.onDownloadVideo(media) },
                onMoveToTop = { actions.onMove(index, 0) }.takeIf { index > 0 },
                onMoveToBottom = { actions.onMove(index, entries.lastIndex) }
                    .takeIf { index < entries.lastIndex },
                modifier = Modifier.reorderable(reorder, index),
                trailing = {
                    with(reorder) {
                        DragHandle(
                            modifier = Modifier.dragHandle(index, entries.size),
                            onRemove = { actions.onRemove(index) },
                        )
                    }
                },
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}

@Composable
private fun QueueHeader(canClear: Boolean, onClear: () -> Unit, listState: LazyListState) {
    CollapsingTitle(title = stringResource(R.string.queue_title), listState = listState) {
        if (canClear) {
            TextButton(onClick = onClear) { Text(stringResource(R.string.queue_clear_all)) }
        }
    }
}

/**
 * Marks the entry the cursor is on — the playing item is a queue member, not a separate box,
 * which is why this stays a label in place rather than a now-playing card above the list.
 *
 * The label alone was easy to miss in a long queue, so it now carries a brand-tinted bar and
 * the item's progress. The progress line is what makes the tab feel like a player surface
 * rather than a list that happens to have one row highlighted.
 */
@Composable
private fun NowPlayingLabel(progress: Float?, isPlaying: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    ) {
        // Dancing bars rather than a static bar: in a long queue you want to spot the item
        // making sound *now*, and motion says that in a way no glyph does. It also
        // distinguishes playing from paused without needing a second symbol.
        PlayingEqualiser(
            playing = isPlaying,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(EqualiserSize),
        )
        Text(
            text = stringResource(R.string.queue_now_playing),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        progress?.let {
            LinearProgressIndicator(
                progress = { it },
                modifier = Modifier
                    .padding(start = 12.dp)
                    .weight(1f)
                    .height(PROGRESS_HEIGHT),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** The header over a run of entries that arrived together. */
@Composable
private fun GroupHeader(
    groupId: String,
    /** Whether this is the group's FIRST run; only that one is tagged, so the tag stays unique. */
    isFirstRun: Boolean,
    title: String,
    /** How many entries this run holds — the point of collapsing is to know without seeing. */
    count: Int,
    collapsed: Boolean,
    /** Whether the item playing right now is inside this run; said so a collapse cannot hide it. */
    containsNowPlaying: Boolean,
    onToggle: () -> Unit,
    onRemoveGroup: () -> Unit,
) {
    val expandOrCollapse = stringResource(
        if (collapsed) R.string.queue_group_expand_action else R.string.queue_group_collapse_action,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            // The whole header toggles, not just the chevron: a 24dp target for something this
            // routine would be a worse version of the drag handle problem.
            .clickable(onClickLabel = expandOrCollapse, onClick = onToggle)
            // Tagged because a UI test cannot otherwise reach this reliably: merged semantics
            // combine TEXT but not ACTIONS, so the node carrying the title has no click, and a
            // coordinate tap on the first row of a lazy list does not land.
            .then(if (isFirstRun) Modifier.testTag(queueGroupHeaderTag(groupId)) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp),
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Filled.ChevronRight else Icons.Filled.ExpandMore,
            contentDescription = stringResource(
                if (collapsed) R.string.queue_group_expand else R.string.queue_group_collapse,
                title,
            ),
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only when collapsed: expanded, the rows are right there and a count is noise.
            if (collapsed) {
                Text(
                    text = if (containsNowPlaying) {
                        pluralStringResource(R.plurals.queue_group_hidden_playing, count, count)
                    } else {
                        pluralStringResource(R.plurals.queue_group_hidden, count, count)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        TextButton(onClick = onRemoveGroup) { Text(stringResource(R.string.queue_remove_group)) }
    }
}

/** The grip to long-press and drag, plus remove. Two controls instead of the old three. */
@Composable
private fun DragHandle(modifier: Modifier, onRemove: () -> Unit) {
    Icon(
        Icons.Filled.DragHandle,
        contentDescription = stringResource(R.string.queue_reorder),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 8.dp),
    )
    IconButton(onClick = onRemove) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove))
    }
}

private val PROGRESS_HEIGHT = 2.dp

/**
 * The header row for one RUN of a queue group, so a UI test can toggle exactly the one it means.
 *
 * Only the group's FIRST header carries it. A group is not necessarily contiguous — queueing
 * something else mid-season splits it, and each part draws its own header when expanded — so
 * tagging every one produced duplicate tags and a test that could not say which it meant.
 * Caught by CI rather than locally, where the split happened to lay out differently.
 */
internal fun queueGroupHeaderTag(groupId: String): String = "queue-group-header-$groupId"

/**
 * How much of the queue is playable with no signal.
 *
 * `fetchedAutomatically` uses the SAME rule the downloader does ([hasAudioOnlyFetch]), so the
 * banner cannot promise a fetch that is never coming.
 */
private fun readinessOf(
    entries: List<QueueEntry>,
    downloads: Map<MediaItemId, DownloadState>,
): OfflineReadiness = OfflineReadiness.of(
    entries.map { it.item.item.id },
    stateOf = { downloads[it] ?: DownloadState.NotDownloaded },
    fetchedAutomatically = { id ->
        entries.firstOrNull { it.item.item.id == id }?.item?.hasAudioOnlyFetch ?: true
    },
)
