package com.dewijones92.totum.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun <T> WrappingTabs(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: (T) -> String,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> Unit,
) {
    val indicator = MaterialTheme.colorScheme.primary
    Box(modifier = modifier.fillMaxWidth()) {
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .testTag(tag(tab))
                        .heightIn(min = TAB_HEIGHT)
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(tab) })
                        .drawBehind { if (isSelected) drawIndicator(indicator) }
                        .padding(horizontal = TAB_PADDING),
                ) { label(tab) }
            }
        }
        HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter).height(1.dp))
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawIndicator(color: Color) {
    val thickness = INDICATOR.toPx()
    drawLine(
        color = color,
        start = Offset(0f, size.height - thickness / 2),
        end = Offset(size.width, size.height - thickness / 2),
        strokeWidth = thickness,
    )
}

private val TAB_HEIGHT = 48.dp
private val TAB_PADDING = 14.dp
private val INDICATOR = 3.dp
