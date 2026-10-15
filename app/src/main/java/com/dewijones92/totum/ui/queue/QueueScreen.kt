package com.dewijones92.totum.ui.queue

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.domain.unavailableOfflineNow
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.queue.GroupDestination
import com.dewijones92.totum.queue.GroupDownload
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.blocks
import com.dewijones92.totum.queue.moveGroup
import com.dewijones92.totum.queue.queueGroupHeaderKey
import com.dewijones92.totum.queue.shuffleAndPlayGroup
import com.dewijones92.totum.queue.shuffleGroup
import com.dewijones92.totum.stats.UseKind
import com.dewijones92.totum.ui.common.BulkAction
import com.dewijones92.totum.ui.common.CollapsingTitle
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.EqualiserSize
import com.dewijones92.totum.ui.common.ExploreToggle
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.FilterField
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.ListFilter
import com.dewijones92.totum.ui.common.LocalLongPressHeldElsewhere
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.NoFilterMatches
import com.dewijones92.totum.ui.common.PlayingEqualiser
import com.dewijones92.totum.ui.common.SelectableMediaList
import com.dewijones92.totum.ui.common.ShuffleAsk
import com.dewijones92.totum.ui.common.SwitchListeningButton
import com.dewijones92.totum.ui.common.filter
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rememberShuffleAsk
import com.dewijones92.totum.ui.common.rowFacts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyListState

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
    val snackbar = remember { SnackbarHostState() }

    // Hoisted so the header can collapse against it — the header sits outside the list, so
    // it cannot read a state the list owns privately.
    val listState = rememberLazyListState()
    val listFilter = rememberListFilter("queue")
    ForgetTheFilterWhenTheQueueEmpties(entries.isEmpty(), listFilter)
    val ask = rememberShuffleAsk()
    // Survives rotation and process death: a collapsed 24-episode season staying
    // collapsed is the entire point, and re-expanding on every return would undo it.
    var collapsedGroups by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val groupIds = remember(entries) { entries.mapNotNullTo(linkedSetOf()) { it.group?.id } }
    val jump = rememberJumpToPlaying(listState, snapshot, collapsedGroups) { collapsedGroups = it }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            QueueHeader(
                entries.size,
                onClear = queue::clear,
                listState = listState,
                listFilter = listFilter,
                onShuffle = shuffleUpNextOrNull(container, snapshot.upNext, ask),
                onShuffleGroups = shuffleGroupsOrNull(container, snapshot.upNext, ask),
                onJump = jump.takeIf { snapshot.currentIndex in entries.indices },
                groups = groupIds to collapsedGroups,
                onCollapseAll = { collapsedGroups = toggledAll(groupIds, collapsedGroups) },
            )
            if (entries.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    headline = stringResource(R.string.queue_title),
                    supportingText = stringResource(R.string.queue_empty),
                )
            } else {
                QueueOfflineSummary(container, entries, downloads)
                val actions = rememberQueueActions(
                    container = container,
                    queue = queue,
                    snackbar = snackbar,
                    scope = scope,
                    collapsedGroups = collapsedGroups,
                    onCollapsedChange = { collapsedGroups = it },
                    ask = ask,
                )
                val reorder = rememberQueueReorder(listState, queue, actions.dragSession) { snapshot }
                FilterableQueue(
                    listFilter, entries, downloads, container, snapshot.currentIndex, playing, listState, reorder,
                    actions,
                )
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Removal as the screen does it: drop the row BY IDENTITY, offer Undo on the snackbar, and put it back
 * at the same index if taken. Identity, not index, because the row a tap meant is the row that must go —
 * an index read at composition is stale the moment anything above it moves (see [PlaybackQueue.remove]).
 */
@Composable
private fun rememberRemoveWithUndo(
    queue: PlaybackQueue,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
): (QueueEntry) -> Unit {
    val removedMessage = stringResource(R.string.queue_removed)
    val undoLabel = stringResource(R.string.queue_undo)
    return remember(queue, snackbar, scope) {
        {
                entry ->
            val index = queue.remove(entry)
            if (index != null) {
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = removedMessage,
                        actionLabel = undoLabel,
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) queue.restoreAt(index, entry)
                }
            }
        }
    }
}

private fun QueueActions.forGroup(id: String) = GroupHeaderActions(
    onToggle = { groups.toggle(id) },
    onRemove = { onRemoveGroup(id) },
    onMove = { onMoveGroup(id, it) },
    onShuffle = { onShuffleGroup(id) },
    onShuffleAndPlay = { onShuffleAndPlayGroup(id) },
    onSetDownload = { onSetGroupDownload(id, it) },
    onPullNew = if (id.startsWith("shuffle:")) { { onPullNew(id) } } else null,
)

/** The row actions, built once per change of their inputs rather than inline in the screen body. */
@Composable
private fun rememberQueueActions(
    container: AppContainer,
    queue: PlaybackQueue,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    collapsedGroups: Set<String>,
    onCollapsedChange: (Set<String>) -> Unit,
    ask: ShuffleAsk,
): QueueActions {
    val dragSession = rememberQueueDragSession()
    val context = LocalContext.current
    val onRemove = rememberRemoveWithUndo(queue, snackbar, scope)
    val groupChoices by container.groupDownloads.choices.collectAsStateWithLifecycle()
    return QueueActions(
        onPlay = queue::jumpTo,
        onPlayInstead = { entry -> scope.launch { queue.playInsteadOfCurrent(entry) } },
        onRemove = onRemove,
        onRemoveGroup = queue::removeGroup,
        onMoveGroup = queue::moveGroup,
        onShuffleGroup = { id ->
            ask { startAtRandom ->
                queue.shuffleGroup(id)
                container.statsLog.use(UseKind.SHUFFLE, "inside a group")
                val after = queue.state.value.let { it.entries.drop((it.currentIndex + 1).coerceAtLeast(0)) }
                val members = after.filter { it.group?.id == id }.map { it.item.item }
                container.randomStarts.assign(members, "group $id", startAtRandom)
            }
        },
        groupDownloading = { groupChoices[it] == GroupDownload.YES },
        onSetGroupDownload = { id, on ->
            container.groupDownloads.choose(id, if (on) GroupDownload.YES else GroupDownload.NO, "the group's menu")
        },
        onShuffleAndPlayGroup = { id ->
            ask { startAtRandom ->
                queue.shuffleAndPlayGroup(id) { members ->
                    container.randomStarts.assign(members.map { it.item.item }, "group $id", startAtRandom)
                }
                container.statsLog.use(UseKind.SHUFFLE, "shuffle and play a group")
            }
        },
        onPullNew = { id -> scope.launch { snackbar.showSnackbar(pulled(context, container, id)) } },
        dragSession = dragSession,
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
            onChange = onCollapsedChange,
        ),
        onDeleteDownload = { id -> scope.launch { container.downloadManager.delete(id) } },
    )
}

/** What a queue row can do — bundled so the row builder isn't a wall of lambdas. */
private data class QueueActions(
    val onPlay: (Int) -> Unit,
    val onPlayInstead: (QueueEntry) -> Unit,
    /** Removal hands over the entry, because the snackbar's Undo needs to put it back. */
    val onRemove: (QueueEntry) -> Unit,
    val onRemoveGroup: (String) -> Unit,
    val onMoveGroup: (String, GroupDestination) -> Unit,
    val onShuffleGroup: (String) -> Unit,
    val onShuffleAndPlayGroup: (String) -> Unit,
    val groupDownloading: (String) -> Boolean,
    val onSetGroupDownload: (String, Boolean) -> Unit,
    val onPullNew: (String) -> Unit,
    val dragSession: QueueDragSession,
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

    fun hides(entry: QueueEntry): Boolean = entry.group?.let { isCollapsed(it.id) } == true

    fun toggle(id: String) {
        onChange(if (id in collapsedIds) collapsedIds - id else collapsedIds + id)
    }
}

/** Where the cursor is and how far through that item playback has got. */
private data class NowPlaying(
    val index: Int,
    val progress: Float?,
    val isPlaying: Boolean,
    val startingId: MediaItemId? = null,
)

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
    reorder: ReorderableLazyListState,
    actions: QueueActions,
) {
    val groups = actions.groups
    val blockCount = entries.blocks().size
    val session = actions.dragSession
    entries.forEachIndexed { index, entry ->
        val group = entry.group
        val startsRun = group != null && entries.getOrNull(index - 1)?.group?.id != group.id
        // A group need not be contiguous — queueing something else mid-season splits it — so each
        // RUN normally draws its own header. Collapsed, all of them are hidden together, and
        // repeating "3 items hidden" once per run would be three lies about the same three items.
        // So a collapsed group shows one header: the first run's.
        val isFirstRunOfGroup = group != null && entries.take(index).none { it.group?.id == group.id }
        val alreadyCollapsedAbove = group != null && groups.isCollapsed(group.id) && !isFirstRunOfGroup
        val splitHeaderMidDrag = !isFirstRunOfGroup && session.dragging
        if (startsRun && !alreadyCollapsedAbove && !splitHeaderMidDrag) {
            val run = entries.filter { it.group?.id == group.id }
            val key = if (isFirstRunOfGroup) queueGroupHeaderKey(group.id) else "group-$index-${group.id}"
            item(key = key) {
                val header: @Composable (QueueRowDrag?) -> Unit = { drag ->
                    GroupHeader(
                        model = GroupHeaderModel(
                            groupId = group.id,
                            isFirstRun = isFirstRunOfGroup,
                            title = group.title,
                            count = run.size,
                            collapsed = groups.isCollapsed(group.id),
                            containsNowPlaying = entries.getOrNull(nowPlaying.index)?.group?.id == group.id,
                            draggable = isFirstRunOfGroup && blockCount > 1,
                            downloading = actions.groupDownloading(group.id),
                        ),
                        actions = actions.forGroup(group.id),
                        drag = drag,
                    )
                }
                if (isFirstRunOfGroup) {
                    ReorderableItem(reorder, key, enabled = session.draggingGroup) { dragging ->
                        header(QueueRowDrag(this, dragging, key, session))
                    }
                } else {
                    header(null)
                }
            }
        }
        // Hidden, not removed: the entry keeps its index, so reordering and "play this" still
        // address the same item once it is shown again.
        if (group != null && groups.isCollapsed(group.id)) return@forEachIndexed
        val key = entry.item.item.id.value
        item(key = key) {
            ReorderableItem(reorder, key) { dragging ->
                QueueRow(
                    entry,
                    index,
                    entries,
                    nowPlaying,
                    availability,
                    QueueRowDrag(this, dragging, key, session),
                    actions
                )
            }
        }
    }
}

@Composable
private fun FilterableQueue(
    listFilter: ListFilter,
    entries: List<QueueEntry>,
    downloads: Map<MediaItemId, DownloadState>,
    container: AppContainer,
    currentIndex: Int,
    playing: PlaybackState?,
    listState: LazyListState,
    reorder: ReorderableLazyListState,
    actions: QueueActions,
) {
    val indexed = remember(entries) { entries.withIndex().toList() }
    val matches = listFilter.filter(indexed, { it.value.item.item.searchableText })
    val availability = QueueAvailability(downloads, container.isOffline())
    val starting by container.playbackQueue.starting.collectAsStateWithLifecycle()
    val nowPlaying = NowPlaying(currentIndex, playing?.progress, playing?.isPlaying == true, starting?.id)
    FilterField(listFilter, matches.size, entries.size, hosted = true)
    SelectableMediaList(
        "queue",
        entries,
        if (listFilter.filtering) matches.map { it.value } else entries.filterNot { actions.groups.hides(it) },
        { it.item.item },
        queueing = false,
        downloadAudioOnly = true,
        extra = { chosen -> queueBulkActions(container.playbackQueue, chosen.map { it.item.item.id }) },
    ) {
        if (!listFilter.filtering) {
            CompositionLocalProvider(
                LocalLongPressHeldElsewhere provides { actions.dragSession.grip.held },
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    item(key = QUEUE_TOP_KEY) { Spacer(Modifier.height(0.dp)) }
                    itemsWithGroupHeaders(
                        availability = availability,
                        entries = entries,
                        nowPlaying = nowPlaying,
                        reorder = reorder,
                        actions = actions,
                    )
                }
            }
        } else if (matches.isEmpty()) {
            NoFilterMatches(listFilter.query)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(matches, key = { it.value.item.item.id.value }) { (index, entry) ->
                    QueueRow(entry, index, entries, nowPlaying, availability, drag = null, actions = actions)
                }
            }
        }
    }
}

private fun queueBulkActions(queue: PlaybackQueue, ids: List<MediaItemId>): List<BulkAction> {
    fun indexOf(id: MediaItemId) = queue.state.value.entries.indexOfFirst { it.item.item.id == id }
    return listOf(
        BulkAction(R.string.queue_remove) {
            ids.forEach { id -> queue.state.value.entries.firstOrNull { it.item.item.id == id }?.let(queue::remove) }
        },
        BulkAction(R.string.queue_move_to_top) {
            ids.asReversed().forEach { id -> indexOf(id).takeIf { it > 0 }?.let { queue.move(it, 0) } }
        },
        BulkAction(R.string.queue_move_to_bottom) {
            ids.forEach { id ->
                val last = queue.state.value.entries.lastIndex
                indexOf(id).takeIf { it in 0 until last }?.let { queue.move(it, last) }
            }
        },
    )
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    index: Int,
    entries: List<QueueEntry>,
    nowPlaying: NowPlaying,
    availability: QueueAvailability,
    drag: QueueRowDrag?,
    actions: QueueActions,
) {
    val media = entry.item.item
    Column {
        if (index == nowPlaying.index) NowPlayingLabel(nowPlaying.progress, nowPlaying.isPlaying)
        val downloadState = availability.stateOf(media.id)
        MediaItemRow(
            item = media,
            // Says why a row will be passed over, rather than leaving it to be discovered.
            // It REPLACES the facts rather than joining them: "this will be skipped" is the
            // only thing worth reading on a row you cannot play.
            subtitleLines = when {
                nowPlaying.startingId == media.id -> listOf(stringResource(R.string.starting_play))
                unavailableOfflineNow(downloadState, availability.offline) ->
                    listOf("${FactEmoji.UNAVAILABLE} ${stringResource(R.string.queue_unavailable_offline)}")
                else -> rowFacts(media, entry.item.pillar)
            },
            // The reorder translation goes on the OUTER element, or a dragged row slides inside a box
            // that stays put — which is what "I can't drag any more" looked like on the phone (cbf9916).
            modifier = Modifier.graphicsLayer {
                val lifted = drag?.isDragging == true
                shadowElevation = if (lifted) DRAG_ELEVATION else 0f
                scaleX = if (lifted) DRAG_SCALE else 1f
                scaleY = scaleX
            },
            downloadState = downloadState,
            pillar = entry.item.pillar,
            onPlay = { actions.onPlay(index) },
            onDownload = { actions.onDownload(media) },
            onDeleteDownload = { actions.onDeleteDownload(media.id) },
            onRemoveFromQueue = { actions.onRemove(entry) },
            onPlayInsteadOfCurrent = { actions.onPlayInstead(entry) }
                .takeIf { nowPlaying.index >= 0 && index != nowPlaying.index },
            onAddToQueue = null,
            onDownloadVideo = { actions.onDownloadVideo(media) },
            onMoveToTop = { actions.onMove(index, 0) }.takeIf { index > 0 },
            onMoveToBottom = { actions.onMove(index, entries.lastIndex) }
                .takeIf { index < entries.lastIndex },
            trailing = drag?.let { row ->
                {
                    QueueDragHandle(
                        row,
                        stringResource(R.string.queue_reorder),
                        Modifier.testTag(queueRowGripTag(media.id.value)),
                    )
                }
            },
        )
    }
}

@Composable
private fun ForgetTheFilterWhenTheQueueEmpties(empty: Boolean, listFilter: ListFilter) {
    var hadEntries by remember { mutableStateOf(false) }
    LaunchedEffect(empty) {
        if (!empty) {
            hadEntries = true
        } else if (hadEntries && listFilter.fieldShown) {
            Diag.log("filter", "queue emptied while filtered by \"${listFilter.query}\", clearing it")
            listFilter.query = ""
            listFilter.open = false
        }
    }
}

@Composable
private fun QueueHeader(
    count: Int,
    onClear: () -> Unit,
    listState: LazyListState,
    listFilter: ListFilter,
    onShuffle: (() -> Unit)? = null,
    onShuffleGroups: (() -> Unit)? = null,
    onJump: (() -> Unit)? = null,
    groups: Pair<Set<String>, Set<String>> = emptySet<String>() to emptySet(),
    onCollapseAll: () -> Unit = {},
) {
    CollapsingTitle(title = stringResource(R.string.queue_title), listState = listState) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterToggle(listFilter, count)
            onJump?.let { JumpToPlayingButton(it) }
            if (groups.first.isNotEmpty()) CollapseAllButton(groups.first.all { it in groups.second }, onCollapseAll)
            SwitchListeningButton("queue header")
            ExploreToggle("queue header")
            onShuffle?.let { QueueShuffleButton(it, onShuffleGroups) }
            if (count > 0) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.queue_clear_all)) }
            }
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

private val PROGRESS_HEIGHT = 2.dp
private const val DRAG_ELEVATION = 12f
private const val QUEUE_TOP_KEY = "queue-top"
private const val DRAG_SCALE = 1.02f

private suspend fun pulled(context: Context, container: AppContainer, groupId: String): String {
    val resources = context.resources
    val report = container.newUploads.check("the group's menu", onlyGroup = groupId)
    return when {
        report.added > 0 -> resources.getQuantityString(R.plurals.queue_pulled_new, report.added, report.added)
        report.failed.isNotEmpty() -> resources.getString(R.string.queue_pull_failed, report.failed.joinToString())
        else -> resources.getString(R.string.queue_pull_nothing)
    }
}
