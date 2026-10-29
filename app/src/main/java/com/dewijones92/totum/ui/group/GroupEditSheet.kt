package com.dewijones92.totum.ui.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import kotlinx.coroutines.launch

private const val MATCHES_SHOWN = 30

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GroupEditSheet(container: AppContainer, group: SourceGroup, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val store = container.sourceGroupStore
    val subscribed by container.accountSubscriptions.channels.collectAsStateWithLifecycle()
    var name by rememberSaveable(group.id.value) { mutableStateOf(group.name) }
    var query by rememberSaveable { mutableStateOf("") }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    val toggle: (MediaSource) -> Unit = { source ->
        scope.launch {
            val now = store.toggleMember(group.id, source)
            Diag.log("group", "\"${group.name}\": ${source.title} ${if (now) "added" else "removed"} from its page")
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.group_edit), style = MaterialTheme.typography.titleLarge)
            NameRow(name, { name = it }, saveable = name.isNotBlank() && name.trim() != group.name) {
                Diag.log("group", "\"${group.name}\" renamed \"${name.trim()}\" from its page")
                scope.launch { store.rename(group.id, name.trim()) }
            }
            Text(stringResource(R.string.group_channels), style = MaterialTheme.typography.titleMedium)
            group.members.forEach { MemberRow(it, member = true, toggle) }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(stringResource(R.string.group_find_channel)) },
                modifier = Modifier.fillMaxWidth().testTag("group-find-channel"),
            )
            matches(subscribed, group, query).forEach { MemberRow(it, member = false, toggle) }
            OutlinedButton(
                onClick = { confirmingDelete = true },
                modifier = Modifier.padding(bottom = 16.dp).testTag("group-delete")
            ) {
                Text(stringResource(R.string.group_delete))
            }
        }
    }
    if (confirmingDelete) {
        DeleteConfirm(group.name, onClose = { confirmingDelete = false }) {
            Diag.log("group", "\"${group.name}\" deleted from its page (${group.members.size} channels)")
            scope.launch { store.delete(group.id) }
            onDismiss()
            onDeleted()
        }
    }
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

private fun matches(subscribed: List<MediaSource.VideoChannel>, group: SourceGroup, query: String): List<MediaSource> {
    val words = query.trim()
    if (words.isEmpty()) return emptyList()
    return subscribed.filter { channel -> channel.id !in group && channel.title.contains(words, ignoreCase = true) }
        .take(MATCHES_SHOWN)
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
