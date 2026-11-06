package com.dewijones92.totum.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SecondaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ScrollableTabs(
    tabs: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    tag: (T) -> String,
    modifier: Modifier = Modifier,
    label: @Composable (T) -> Unit,
) {
    SecondaryScrollableTabRow(
        selectedTabIndex = tabs.indexOf(selected).coerceAtLeast(0),
        edgePadding = 0.dp,
        modifier = modifier,
    ) {
        tabs.forEach { tab ->
            Tab(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                text = { label(tab) },
                modifier = Modifier.testTag(tag(tab)),
            )
        }
    }
}
