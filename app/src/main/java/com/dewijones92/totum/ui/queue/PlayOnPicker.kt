package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.targets.PlayOn
import com.dewijones92.totum.data.targets.PlayOnHint
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.targets.TargetsRepository

@Composable
fun PlayOnRow(targets: TargetsRepository, padStatusBar: Boolean, modifier: Modifier = Modifier) {
    if (!targets.available) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(targets, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { targets.watchWhile(IDLE_WATCH_MS) }
    }
    val options by targets.options.collectAsStateWithLifecycle()
    val address by targets.browserAddress.collectAsStateWithLifecycle()
    if (options.size <= 1 && address == null) return
    val selected by targets.selected.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val current = PlayOn.resolve(selected, options)
    var open by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .then(if (padStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag(PLAY_ON_ROW_TAG),
    ) {
        Text(stringResource(R.string.play_on_label), style = MaterialTheme.typography.labelLarge)
        Box {
            TargetChip(
                label = stringResource(R.string.play_on_current, current.target.chipLabel(current.power)),
                colour = TargetColours.of(colours, current.id).asColour(),
                selected = true,
                onClick = { open = true },
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                PlayOnMenuItems(options, colours, current.id) {
                    open = false
                    targets.choose(it)
                }
                address?.let { AddBrowserEntry(it) }
            }
        }
    }
}

@Composable
internal fun PlayOnMenuItems(
    options: List<PlayOnOption>,
    colours: Map<String, Long>,
    currentId: String?,
    onPick: (PlayOnOption) -> Unit,
) {
    options.forEach { option ->
        DropdownMenuItem(
            leadingIcon = {
                Box(
                    Modifier
                        .size(DOT)
                        .testTag(PLAY_ON_DOT_PREFIX + option.id)
                        .background(TargetColours.of(colours, option.id).asColour(), CircleShape),
                )
            },
            text = {
                Column {
                    val tick = if (option.id == currentId) " ✅" else ""
                    Text(option.target.chipLabel(option.power) + tick)
                    PlayOnHintLine(option)
                }
            },
            onClick = { onPick(option) },
            modifier = Modifier.testTag(PLAY_ON_OPTION_PREFIX + option.id),
        )
    }
}

@Composable
internal fun AddBrowserEntry(address: String, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(
        modifier
            .fillMaxWidth()
            .testTag(ADD_BROWSER_TAG)
            .clickable { open = !open }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(stringResource(R.string.play_on_add_browser))
        if (open) {
            Text(
                stringResource(R.string.play_on_add_browser_how, address),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp).testTag(ADD_BROWSER_HOW_TAG),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                Text(stringResource(R.string.play_on_copy_address))
            }
        }
    }
}

@Composable
internal fun PlayOnHintLine(option: PlayOnOption) {
    val hint = when (option.hint) {
        PlayOnHint.NONE -> return
        PlayOnHint.WAKES_WHEN_PICKED -> R.string.play_on_asleep_hint
        PlayOnHint.SWITCH_ON_BY_HAND -> R.string.play_on_by_hand_hint
    }
    Text(stringResource(hint), style = MaterialTheme.typography.bodySmall)
}

const val ADD_BROWSER_TAG = "play-on-add-browser"
const val ADD_BROWSER_HOW_TAG = "play-on-add-browser-how"
const val PLAY_ON_ROW_TAG = "play-on-row"
const val PLAY_ON_OPTION_PREFIX = "play-on-option-"
const val PLAY_ON_DOT_PREFIX = "play-on-dot-"
private const val IDLE_WATCH_MS = 10_000L
private val DOT = 12.dp
