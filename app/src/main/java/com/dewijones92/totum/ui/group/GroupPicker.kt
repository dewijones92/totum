package com.dewijones92.totum.ui.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceGroupId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.ui.common.FilterableList

/**
 * "Which groups is this source in?" — the whole of group management, in one dialog.
 *
 * A checklist rather than a screen because membership is a toggle and a source is usually
 * in one or two groups: a dedicated management screen would be a longer road to the same
 * two taps. Creating a group is on the same dialog for the same reason — the moment you
 * want a new group is the moment you have something to put in it, and sending the user
 * elsewhere to make an empty one first is a worse version of the same journey.
 */
@Composable
internal fun GroupPicker(
    sourceId: SourceId,
    groups: List<SourceGroup>,
    onToggle: (SourceGroup) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (SourceGroup, String) -> Unit,
    onDelete: (SourceGroup) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    // Which row is being renamed, if any. Renaming in place rather than in a second dialog:
    // a dialog on top of a dialog to change one word is a lot of ceremony for one word.
    var renaming by remember { mutableStateOf<SourceGroupId?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_title)) },
        text = {
            Column {
                if (groups.isEmpty()) {
                    Text(stringResource(R.string.groups_none_yet))
                }
                FilterableList("groups", groups, { listOf(it.name) }, inset = 0.dp) { shown, _ ->
                    LazyColumn(modifier = Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                        items(shown, key = { it.id.value }) { group ->
                            GroupRow(
                                group = group,
                                member = sourceId in group,
                                renaming = renaming == group.id,
                                onToggle = { onToggle(group) },
                                onStartRename = { renaming = group.id },
                                onRename = { name ->
                                    renaming = null
                                    if (name.isNotBlank() && name != group.name) onRename(group, name)
                                },
                                onDelete = { onDelete(group) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(stringResource(R.string.groups_new)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                // Blank names are rejected by the domain type, so the button simply cannot
                // offer to make one — better than an error explaining a rule after the fact.
                enabled = newName.isNotBlank(),
                onClick = {
                    onCreate(newName.trim())
                    newName = ""
                },
            ) { Text(stringResource(R.string.groups_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
    )
}

/** One group: its membership checkbox, its name (editable in place), and its actions. */
@Composable
private fun GroupRow(
    group: SourceGroup,
    member: Boolean,
    renaming: Boolean,
    onToggle: () -> Unit,
    onStartRename: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = {
            if (renaming) {
                var draft by remember(group.id) { mutableStateOf(group.name) }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { onRename(draft.trim()) }),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    trailingIcon = {
                        IconButton(onClick = { onRename(draft.trim()) }) {
                            Icon(Icons.Outlined.Check, contentDescription = stringResource(R.string.done))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(group.name)
            }
        },
        leadingContent = { Checkbox(checked = member, onCheckedChange = { onToggle() }) },
        trailingContent = {
            if (!renaming) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.groups_edit))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.groups_rename)) },
                            onClick = {
                                menuOpen = false
                                onStartRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.groups_delete)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private val LIST_MAX_HEIGHT = 280.dp
