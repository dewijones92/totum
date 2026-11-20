package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.targets.TargetColours

@Composable
internal fun TargetColourMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    label: String,
    current: Long,
    onPick: (Long) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.target_colour_menu, label),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(SWATCH_GAP),
            verticalArrangement = Arrangement.spacedBy(SWATCH_GAP),
            modifier = Modifier.padding(horizontal = 12.dp).widthIn(max = SWATCH_ROW_MAX),
        ) {
            TargetColours.palette.forEachIndexed { index, swatch ->
                val description = stringResource(R.string.target_colour_swatch, index + 1)
                Box(
                    Modifier
                        .size(SWATCH)
                        .clip(CircleShape)
                        .background(swatch.asColour())
                        .then(
                            if (swatch == current) {
                                Modifier.border(SWATCH_RING, MaterialTheme.colorScheme.onSurface, CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clickable(role = Role.Button) {
                            onPick(swatch)
                            onDismiss()
                        }
                        .semantics { contentDescription = description }
                        .testTag(SWATCH_TAG_PREFIX + index),
                )
            }
        }
    }
}

const val SWATCH_TAG_PREFIX = "target-swatch-"
internal val SWATCH = 48.dp
private val SWATCH_GAP = 8.dp
private val SWATCH_RING = 3.dp
private val SWATCH_ROW_MAX = 224.dp
