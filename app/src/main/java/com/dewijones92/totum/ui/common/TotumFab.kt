package com.dewijones92.totum.ui.common

import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's floating action button.
 *
 * Exists so the brand's boldness lives in one place: Material 3 defaults a FAB to
 * `primaryContainer`, which in Totum's palette is a pale tint and reads timid against a
 * deliberately bright brand. Solid `primary` with white content is what the brief asked
 * for, and putting it here means a new screen gets it without knowing the rule.
 */
@Composable
internal fun TotumFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        shape = MaterialTheme.shapes.large,
        modifier = modifier,
        content = content,
    )
}
