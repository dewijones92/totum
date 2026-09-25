package com.dewijones92.totum.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.ui.common.CheckedOptionsMenu

@Composable
internal fun ControlTile(
    icon: ImageVector,
    label: String,
    value: String?,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onToggle: ((Boolean) -> Unit)? = null,
    iconDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (active) scheme.primaryContainer else scheme.surfaceContainerHigh.copy(alpha = IDLE_TILE_ALPHA),
        label = "tile-container",
    )
    val content = if (active) scheme.onPrimaryContainer else scheme.onSurface
    val interaction = when {
        onToggle != null -> Modifier.toggleable(value = active, role = Role.Switch, onValueChange = onToggle)
        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier.heightIn(min = TILE_MIN_HEIGHT),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = interaction.padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) scheme.primary else Color.Transparent,
                contentColor = if (active) scheme.onPrimary else scheme.onSurfaceVariant,
                modifier = Modifier.size(ICON_BUBBLE),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = iconDescription, modifier = Modifier.size(20.dp))
                }
            }
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
                value?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) content else scheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ToggleTile(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val state = stringResource(if (checked) R.string.control_on else R.string.control_off)
    ControlTile(
        icon = icon,
        label = label,
        value = detail?.let { "$state · $it" } ?: state,
        active = checked,
        onToggle = onCheckedChange,
        modifier = modifier,
    )
}

@Composable
internal fun <T> PickerTile(
    icon: ImageVector,
    label: String,
    current: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        ControlTile(
            icon = icon,
            label = label,
            value = optionLabel(current),
            active = active,
            onClick = { open = true },
            iconDescription = label,
            modifier = Modifier.fillMaxSize(),
        )
        CheckedOptionsMenu(
            expanded = open,
            onDismiss = { open = false },
            options = options,
            current = current,
            label = optionLabel,
            onSelect = onSelect,
        )
    }
}

private val TILE_MIN_HEIGHT = 64.dp
private val ICON_BUBBLE = 36.dp
private const val IDLE_TILE_ALPHA = 0.75f
