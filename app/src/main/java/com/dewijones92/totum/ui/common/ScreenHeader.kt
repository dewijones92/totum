package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val style = MaterialTheme.typography.headlineLarge
    TitleAndActions(
        titleWidth = rememberTextWidth(title, style, wholeLine = false),
        modifier = modifier.padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        title = { place ->
            Column(place) {
                Text(
                    text = title,
                    style = style,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = { Row(verticalAlignment = Alignment.CenterVertically) { actions() } },
    )
}
