package com.dewijones92.totum.ui.common

import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem

@Stable
class Selection internal constructor(val place: String, private val state: MutableState<Set<String>>) {
    val ids: Set<String> get() = state.value

    val active: Boolean get() = state.value.isNotEmpty()

    fun isSelected(id: String): Boolean = id in state.value

    fun toggle(id: String) {
        val before = state.value
        state.value = if (id in before) before - id else before + id
        if (before.isEmpty()) Diag.log("select", "$place selection started")
    }

    fun selectAll(all: Collection<String>) {
        state.value = state.value + all
        Diag.log("select", "$place select all -> ${state.value.size} selected")
    }

    fun clear(why: String) {
        if (state.value.isEmpty()) return
        Diag.log("select", "$place selection of ${state.value.size} cleared ($why)")
        state.value = emptySet()
    }

    internal fun retainOnly(present: Set<String>) {
        val kept = state.value.intersect(present)
        if (kept.size != state.value.size) state.value = kept
    }
}

@Composable
fun rememberSelection(place: String, key: Any? = null): Selection {
    val state = rememberSaveable(
        key,
        saver = listSaver<MutableState<Set<String>>, String>(
            save = { it.value.toList() },
            restore = { mutableStateOf(it.toSet()) },
        ),
    ) { mutableStateOf(emptySet()) }
    return remember(place, state) { Selection(place, state) }
}

internal val LocalRowSelection = compositionLocalOf<Selection?> { null }

val LocalLongPressHeldElsewhere = compositionLocalOf<() -> Boolean> { { false } }

data class BulkAction(
    @param:StringRes val label: Int,
    @param:PluralsRes val confirm: Int? = null,
    val run: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.selectableClicks(id: String, enabled: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)?): Modifier {
    val selection = LocalRowSelection.current
    val selecting = selection?.active == true
    val heldElsewhere = LocalLongPressHeldElsewhere.current
    return combinedClickable(
        enabled = selection != null || enabled,
        onClick = { if (selecting) selection?.toggle(id) else if (enabled) onClick() },
        onLongClick = if (selection != null) {
            {
                Diag.log("select", "dewidebug long-press ${selection.place} heldElsewhere=${heldElsewhere()}")
                if (heldElsewhere()) {
                    Diag.log(
                        "select",
                        "${selection.place} long-press on a control, not selecting"
                    )
                } else {
                    selection.toggle(id)
                }
            }
        } else {
            onLongClick
        },
    )
}

@Composable
fun isRowSelected(id: String): Boolean = LocalRowSelection.current?.isSelected(id) == true

@Composable
fun isSelecting(): Boolean = LocalRowSelection.current?.active == true

@Composable
fun SelectionCheckbox(id: String, label: String) {
    val selection = LocalRowSelection.current ?: return
    Checkbox(
        checked = selection.isSelected(id),
        onCheckedChange = { selection.toggle(id) },
        modifier = Modifier.semantics { contentDescription = label },
    )
}

@Composable
fun Color.orSelected(id: String): Color = if (isRowSelected(id)) selectedRowTint().compositeOver(this) else this

@Composable
fun <T> SelectableList(
    selection: Selection,
    items: List<T>,
    visible: List<T>,
    key: (T) -> String,
    actions: (selected: List<T>) -> List<BulkAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val present = remember(items) { items.map(key).toSet() }
    LaunchedEffect(present) { selection.retainOnly(present) }
    BackHandler(enabled = selection.active) { selection.clear("back") }
    Column(modifier) {
        if (selection.active) {
            val chosen = items.filter { selection.isSelected(key(it)) }
            SelectionBar(
                selection = selection,
                count = chosen.size,
                onSelectAll = { selection.selectAll(visible.map(key)) },
                actions = actions(chosen),
            )
        }
        Box(Modifier.weight(1f)) {
            CompositionLocalProvider(LocalRowSelection provides selection) { content() }
        }
    }
}

@Composable
private fun SelectionBar(
    selection: Selection,
    count: Int,
    onSelectAll: () -> Unit,
    actions: List<BulkAction>,
    modifier: Modifier = Modifier,
) {
    var confirming by remember { mutableStateOf<BulkAction?>(null) }
    confirming?.let { action ->
        ConfirmBulk(action, count, onDismiss = { confirming = null }) {
            confirming = null
            Diag.log("select", "${selection.place} confirmed on $count item(s)")
            action.run()
            selection.clear("confirmed")
        }
    }
    Surface(
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().testTag(SELECTION_BAR_TAG)
    ) {
        Column(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selection.clear("closed") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.select_done))
                }
                Text(
                    pluralStringResource(R.plurals.selected_count, count, count),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSelectAll) { Text(stringResource(R.string.select_all)) }
            }
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
            ) {
                items(actions, key = { it.label }) { action ->
                    val label = stringResource(action.label)
                    val run = {
                        Diag.log("select", "${selection.place} \"$label\" on $count item(s)")
                        action.run()
                        selection.clear("\"$label\" done")
                    }
                    AssistChip(
                        onClick = { if (action.confirm == null) run() else confirming = action },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConfirmBulk(action: BulkAction, count: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val confirm = action.confirm ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(action.label)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        text = { Text(pluralStringResource(confirm, count, count)) },
    )
}

@Composable
fun mediaBulkActions(): (List<MediaItem>) -> List<BulkAction> {
    val actions = LocalItemActions.current
    val downloads = LocalDownloadStates.current
    return { chosen ->
        val common = actions?.let { a ->
            val anyDownloaded = chosen.any { downloads[it.id] is DownloadState.Downloaded }
            val anyMissing = chosen.any { downloads[it.id] !is DownloadState.Downloaded }
            listOfNotNull(
                BulkAction(R.string.queue_play_next) { chosen.asReversed().forEach(a::playNext) },
                BulkAction(R.string.queue_add) { chosen.forEach(a::addToQueue) },
                BulkAction(R.string.playlist_add_to) { a.addToPlaylist(chosen) },
                BulkAction(
                    R.string.download
                ) { chosen.forEach { a.download(it, audioOnly = false) } }.takeIf { anyMissing },
                BulkAction(R.string.download_delete) {
                    chosen.filter { downloads[it.id] is DownloadState.Downloaded }.forEach { a.deleteDownload(it.id) }
                }.takeIf { anyDownloaded },
                BulkAction(R.string.mark_played) { chosen.forEach { a.setPlayed(it.id, true) } },
                BulkAction(R.string.mark_unplayed) { chosen.forEach { a.setPlayed(it.id, false) } },
            )
        }.orEmpty()
        common
    }
}

const val SELECTION_BAR_TAG: String = "selection-bar"

@Composable
fun <T> SelectableMediaList(
    place: String,
    items: List<T>,
    visible: List<T>,
    media: (T) -> MediaItem,
    modifier: Modifier = Modifier,
    key: Any? = null,
    extra: (List<T>) -> List<BulkAction> = { emptyList() },
    content: @Composable () -> Unit,
) {
    val selection = rememberSelection(place, key)
    val bulk = mediaBulkActions()
    SelectableList(
        selection,
        items,
        visible,
        { media(it).id.value },
        { chosen -> extra(chosen) + bulk(chosen.map(media)) },
        modifier,
        content
    )
}
