package com.dewijones92.totum.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.navigation.TopLevelDestination
import com.dewijones92.totum.playback.PlaybackState

@Composable
fun Dock(
    state: PlaybackState?,
    selected: TopLevelDestination,
    onSelect: (TopLevelDestination) -> Unit,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit,
    onSkipNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(DOCK_CORNER),
        color = dockColor(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = DOCK_BORDER_ALPHA)),
        shadowElevation = DOCK_SHADOW,
        modifier = modifier
            .navigationBarsPadding()
            .padding(start = DOCK_GUTTER, end = DOCK_GUTTER, bottom = DOCK_GUTTER)
            .fillMaxWidth(),
    ) {
        Column {
            state?.let {
                MiniPlayerBar(
                    state = it,
                    onTogglePlayPause = onTogglePlayPause,
                    onExpand = onExpand,
                    onSkipNext = onSkipNext,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = DOCK_BORDER_ALPHA),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            DockNavigation(selected, onSelect)
        }
    }
}

@Composable
internal fun dockColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < DARK_LUMINANCE
    return if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest
}

@Composable
private fun DockNavigation(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 6.dp),
    ) {
        TopLevelDestination.entries.forEach { destination ->
            DockItem(
                destination = destination,
                selected = destination == selected,
                onClick = { onSelect(destination) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DockItem(
    destination: TopLevelDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val pill by animateColorAsState(if (selected) scheme.primary else Color.Transparent, label = "dock-pill")
    val iconTint by animateColorAsState(
        if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        label = "dock-icon"
    )
    val labelTint by animateColorAsState(
        if (selected) scheme.primary else scheme.onSurfaceVariant,
        label = "dock-label"
    )
    val scale by animateFloatAsState(if (selected) SELECTED_SCALE else 1f, label = "dock-scale")
    val label = stringResource(destination.labelRes)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(vertical = 2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = PILL_WIDTH, height = PILL_HEIGHT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(pill, RoundedCornerShape(PILL_HEIGHT / 2)),
        ) {
            Icon(
                imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                contentDescription = label,
                tint = iconTint,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = labelTint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val DOCK_CORNER = 28.dp
private val DOCK_GUTTER = 10.dp
private val DOCK_SHADOW = 12.dp
private const val DOCK_BORDER_ALPHA = 0.7f
private const val DARK_LUMINANCE = 0.5f
private val PILL_WIDTH = 56.dp
private val PILL_HEIGHT = 32.dp
private const val SELECTED_SCALE = 1.06f
