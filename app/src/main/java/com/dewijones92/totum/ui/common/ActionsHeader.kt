package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
fun ActionsHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val style = MaterialTheme.typography.headlineLarge
    TitleAndActions(
        titleWidth = rememberTextWidth(title, style, wholeLine = true),
        itemAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        title = { place ->
            Text(
                text = title,
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = place.semantics { heading() },
            )
        },
        actions = trailing,
    )
}
