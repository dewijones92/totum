package com.dewijones92.totum.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.ui.queue.AddBrowserDialog
import com.dewijones92.totum.ui.queue.AddBrowserEntry
import com.dewijones92.totum.ui.queue.PLAY_ON_DOT_PREFIX
import com.dewijones92.totum.ui.queue.PLAY_ON_OPTION_PREFIX
import com.dewijones92.totum.ui.queue.PLAY_ON_UNREACHABLE_TAG
import com.dewijones92.totum.ui.queue.PlayOnHintLine
import com.dewijones92.totum.ui.queue.asColour
import com.dewijones92.totum.ui.queue.chipLabel

@Composable
internal fun PlayOnSheetAction(item: MediaItem?, onDismiss: () -> Unit) {
    val actions = LocalItemActions.current ?: return
    val devices = actions.devices ?: return
    val options by devices.options.collectAsStateWithLifecycle()
    val colours by devices.colours.collectAsStateWithLifecycle()
    val address by devices.browserAddress.collectAsStateWithLifecycle()
    val unreachable by devices.homeUnreachable.collectAsStateWithLifecycle()
    val anywhereToPlay = options.size > 1 || address != null
    if (item == null || item.toPlayableOrNull() == null) return
    if (!anywhereToPlay) {
        if (unreachable) {
            SheetRow(Icons.Outlined.Cast, stringResource(R.string.play_on_home_unreachable), PLAY_ON_UNREACHABLE_TAG) {}
        }
        return
    }
    var open by remember(item.id) { mutableStateOf(false) }
    var adding by remember(item.id) { mutableStateOf(false) }
    SheetRow(Icons.Outlined.Cast, stringResource(R.string.play_on_item), PLAY_ON_SHEET_TAG) { open = !open }
    if (open) {
        Column {
            options.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .testTag(PLAY_ON_OPTION_PREFIX + option.id)
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            actions.playOn(item, option)
                        }
                        .padding(start = 72.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    Box(
                        Modifier
                            .padding(end = 12.dp)
                            .size(12.dp)
                            .testTag(PLAY_ON_DOT_PREFIX + option.id)
                            .background(TargetColours.of(colours, option.id).asColour(), CircleShape),
                    )
                    Column {
                        Text(option.target.chipLabel(option.power), style = MaterialTheme.typography.bodyLarge)
                        PlayOnHintLine(option)
                    }
                }
            }
            address?.let { AddBrowserEntry({ adding = true }, Modifier.padding(start = 56.dp)) }
        }
    }
    address?.takeIf { adding }?.let { AddBrowserDialog(it) { adding = false } }
}

const val PLAY_ON_SHEET_TAG = "play-on-sheet"
