package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.ui.common.pulsing

data class LibraryGroup(val id: String, val name: String, val channels: Int, val fresh: Int)

data class LibraryGroups(
    val groups: List<LibraryGroup> = emptyList(),
    val onOpen: (String) -> Unit = {},
    val onCreate: (String) -> Unit = {},
)

@Composable
internal fun GroupTiles(groups: LibraryGroups) {
    val resources = LocalResources.current
    val scheme = MaterialTheme.colorScheme
    var naming by rememberSaveable { mutableStateOf(false) }
    val tiles = groups.groups.map { group ->
        @Composable { modifier: Modifier ->
            LibraryTile(
                if (group.fresh > 0) Icons.Outlined.NewReleases else Icons.Outlined.Folder,
                group.name,
                if (group.fresh > 0) scheme.primary else scheme.surfaceContainerHigh,
                if (group.fresh > 0) scheme.onPrimary else scheme.onSurface,
                { groups.onOpen(group.id) },
                modifier.pulsing(group.fresh > 0, group.fresh).testTag("library-group-${group.name}"),
                detail = if (group.fresh > 0) {
                    resources.getQuantityString(R.plurals.group_tile_new, group.fresh, group.fresh)
                } else {
                    resources.getQuantityString(R.plurals.group_tile_channels, group.channels, group.channels)
                },
            )
        }
    } + listOf<@Composable (Modifier) -> Unit>({ modifier ->
        LibraryTile(
            Icons.Outlined.Add,
            stringResource(R.string.group_new),
            scheme.surfaceContainer,
            scheme.onSurface,
            { naming = true },
            modifier.testTag("library-group-new"),
        )
    })
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.group_section), style = MaterialTheme.typography.titleMedium)
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                pair.forEach { tile -> tile(Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    if (naming) NewGroupDialog(onCreate = groups.onCreate) { naming = false }
}

@Composable
private fun NewGroupDialog(onCreate: (String) -> Unit, onClose: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.group_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.group_name)) },
                modifier = Modifier.fillMaxWidth().testTag("library-group-new-name"),
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(name.trim())
                    onClose()
                },
                modifier = Modifier.testTag("library-group-new-create"),
            ) { Text(stringResource(R.string.groups_create)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.cancel)) } },
    )
}
