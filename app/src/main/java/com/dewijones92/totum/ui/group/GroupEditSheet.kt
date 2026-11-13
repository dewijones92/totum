package com.dewijones92.totum.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.FuzzyMatch
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.fuzzyFiltered
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo
import com.dewijones92.totum.ui.common.rememberSnackbarOffers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditSheet(container: AppContainer, group: SourceGroup, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = container.sourceGroupStore
    val subscribed by container.accountSubscriptions.channels.collectAsStateWithLifecycle()
    var name by rememberSaveable(group.id.value) { mutableStateOf(group.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val flip: (MediaSource, String) -> Unit = { source, how ->
        scope.launch {
            val now = store.toggleMember(group.id, source)
            Diag.log(
                "group",
                "\"${group.name}\": ${source.title} ${if (now) "added" else "removed"} from its page ($how)",
            )
        }
    }
    val removeWithUndo = rememberRemoveWithUndo(
        offers = rememberSnackbarOffers(snackbar),
        undoLabel = stringResource(R.string.undo),
        tag = "group",
        removedMessage = { source: MediaSource -> resources.getString(R.string.group_member_removed, source.title) },
        remove = { source: MediaSource -> source.also { flip(it, "unticked") } },
        restore = { source: MediaSource -> flip(source, "undo") },
    )
    val toggle: (MediaSource) -> Unit = { source ->
        if (source.id in group) removeWithUndo(source) else flip(source, "ticked")
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Box(Modifier.fillMaxWidth()) {
            val found = remember(subscribed, group, query) { matches(subscribed, group, query) }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                item { Text(stringResource(R.string.group_edit), style = MaterialTheme.typography.titleLarge) }
                item {
                    NameRow(name, { name = it }, saveable = name.isNotBlank() && name.trim() != group.name) {
                        Diag.log("group", "\"${group.name}\" renamed \"${name.trim()}\" from its page")
                        scope.launch { store.rename(group.id, name.trim()) }
                    }
                }
                item { Text(stringResource(R.string.group_channels), style = MaterialTheme.typography.titleMedium) }
                items(group.members, key = { "member:${it.id.value}" }) { MemberRow(it, member = true, toggle) }
                findChannel(query, { query = it }, found, toggle)
                item {
                    OutlinedButton(
                        onClick = { confirmingDelete = true },
                        modifier = Modifier.padding(bottom = 16.dp).testTag("group-delete")
                    ) {
                        Text(stringResource(R.string.group_delete))
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (confirmingDelete) {
        DeleteConfirm(group.name, onClose = { confirmingDelete = false }) {
            Diag.log("group", "\"${group.name}\" deleted from its page (${group.members.size} channels)")
            container.applicationScope.launch { store.delete(group.id) }
            onDismiss()
            onDeleted()
        }
    }
}

private fun LazyListScope.findChannel(
    query: String,
    onQuery: (String) -> Unit,
    found: List<MediaSource>,
    toggle: (MediaSource) -> Unit,
) {
    item {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.group_find_channel)) },
            modifier = Modifier.fillMaxWidth().testTag("group-find-channel"),
        )
    }
    if (query.isNotBlank()) {
        item {
            Text(
                text = pluralStringResource(R.plurals.group_find_count, found.size, found.size, query.trim()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(found, key = { "match:${it.id.value}" }) { MemberRow(it, member = false, toggle) }
}

@Composable
private fun NameRow(name: String, onName: (String) -> Unit, saveable: Boolean, onSave: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            label = { Text(stringResource(R.string.group_name)) },
            modifier = Modifier.weight(1f).testTag("group-name"),
        )
        TextButton(enabled = saveable, onClick = onSave, modifier = Modifier.testTag("group-rename")) {
            Text(stringResource(R.string.group_save_name))
        }
    }
}

@Composable
private fun DeleteConfirm(name: String, onClose: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.group_delete_confirm, name)) },
        confirmButton = {
            TextButton(
                onClick = {
                    onClose()
                    onDelete()
                },
                modifier = Modifier.testTag("group-delete-confirm"),
            ) { Text(stringResource(R.string.group_delete)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}

internal fun matches(
    subscribed: List<MediaSource.VideoChannel>,
    group: SourceGroup,
    query: String,
): List<MediaSource> {
    if (!FuzzyMatch.hasTerms(query)) return emptyList()
    return subscribed.filter { channel -> channel.id !in group }.fuzzyFiltered(query) { it.searchableText }
}

@Composable
private fun MemberRow(source: MediaSource, member: Boolean, toggle: (MediaSource) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { toggle(source) }.testTag("group-member-${source.title}"),
    ) {
        Checkbox(checked = member, onCheckedChange = { toggle(source) })
        Text(source.title, style = MaterialTheme.typography.bodyLarge)
    }
}
