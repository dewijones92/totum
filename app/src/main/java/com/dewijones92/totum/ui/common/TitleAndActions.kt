package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp

/**
 * A title on the left with its actions on the right, the actions moving onto a line below when
 * they would leave the title less than [titleWidth]. Every screen title goes through this, so no
 * header squeezes its title to a letter or breaks a word to make room for icons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TitleAndActions(
    titleWidth: Dp,
    modifier: Modifier = Modifier,
    itemAlignment: Alignment.Vertical = Alignment.CenterVertically,
    title: @Composable (Modifier) -> Unit,
    actions: @Composable () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.End,
        itemVerticalAlignment = itemAlignment,
        modifier = modifier.fillMaxWidth(),
    ) {
        title(Modifier.weight(1f).widthIn(min = titleWidth))
        actions()
        SearchAction()
    }
}

@Composable
internal fun rememberTextWidth(text: String, style: TextStyle, wholeLine: Boolean): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(text, style, density, wholeLine) {
        val pieces = if (wholeLine) listOf(text) else text.split(' ')
        val widest = pieces.maxOf { measurer.measure(it, style).size.width }
        with(density) { widest.toDp() }
    }
}
