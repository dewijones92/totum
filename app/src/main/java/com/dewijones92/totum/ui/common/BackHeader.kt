package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R

/**
 * Header for a screen reached from inside another one: back, title, and an optional
 * trailing action. Used by the layers hosted under a tab (history, account, …) so
 * they all get back out the same way. Source pages use [SourceHeader] instead —
 * same idea, but its trailing slot is a subscribe toggle.
 */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    trailing: @Composable () -> Unit = {},
) {
    val style = MaterialTheme.typography.headlineSmall
    TitleAndActions(
        titleWidth = rememberTextWidth(title, style, wholeLine = false) + BACK_AND_GUTTERS,
        modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        title = { place ->
            Row(place, verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(text = title, style = style)
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        actions = { Row(verticalAlignment = Alignment.CenterVertically) { trailing() } },
        standardActions = { CastingAction() },
    )
}

private val BACK_AND_GUTTERS = 76.dp

@Composable
fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    FilledTonalIconButton(
        onClick = onBack,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
    ) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
    }
}
