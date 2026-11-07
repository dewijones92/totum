package com.dewijones92.totum.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.NOTE_MAX_CHARS

/**
 * Asks what went wrong before sending, because the reader's problem is knowing WHICH of four
 * hundred events to look at.
 *
 * Sending is never blocked on it — an empty box still sends, and that is deliberate: the worst
 * outcome here is a report that does not get sent because writing a description felt like work.
 * See [diagnosticsNote].
 */
@Composable
@Suppress("LongParameterList")
internal fun DiagnosticsNoteDialog(
    onDismiss: () -> Unit,
    onSend: (String, List<EncodedScreenshot>) -> Unit,
    @StringRes supporting: Int = R.string.settings_diagnostics_note_supporting,
    @StringRes confirm: Int = R.string.settings_diagnostics_send,
    withScreenshots: Boolean = true,
    initial: String = "",
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    var typed by rememberSaveable { mutableStateOf(initial) }
    val shots = rememberScreenshotPicker()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_diagnostics_note_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(supporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it.take(NOTE_MAX_CHARS) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .testTag("diagnostics-note"),
                    placeholder = { Text(stringResource(R.string.settings_diagnostics_note_hint)) },
                    minLines = 3,
                    maxLines = 6,
                )
                if (withScreenshots) ScreenshotPickerRow(shots)
                extra()
            }
        },
        confirmButton = {
            TextButton(enabled = !shots.preparing, onClick = { onSend(typed, shots.ready) }) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
