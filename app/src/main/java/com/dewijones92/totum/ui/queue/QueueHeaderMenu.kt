package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.ui.common.ExploreMenuItem
import com.dewijones92.totum.ui.common.LocalExploreMode
import com.dewijones92.totum.ui.common.LocalListeningSwitch
import com.dewijones92.totum.ui.common.SwitchListeningMenuItem

@Composable
internal fun QueueHeaderMenu(allCollapsed: Boolean?, onCollapseAll: () -> Unit, onClear: (() -> Unit)?) {
    val offered = listOf(
        allCollapsed != null,
        LocalListeningSwitch.current?.target != null,
        LocalExploreMode.current != null,
        onClear != null,
    )
    if (offered.none { it }) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(QUEUE_HEADER_MENU_TAG)) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.queue_header_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val close = { open = false }
            allCollapsed?.let { collapsed ->
                MenuItem(
                    if (collapsed) R.string.queue_expand_all else R.string.queue_collapse_all,
                    close,
                    onCollapseAll
                )
            }
            SwitchListeningMenuItem(QUEUE_HEADER, close)
            ExploreMenuItem(QUEUE_HEADER, close)
            onClear?.let { MenuItem(R.string.queue_clear_all, close, it) }
        }
    }
}

private const val QUEUE_HEADER = "queue header"
const val QUEUE_HEADER_MENU_TAG = "queue-header-menu"
