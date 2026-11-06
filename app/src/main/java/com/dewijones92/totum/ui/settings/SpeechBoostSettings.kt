package com.dewijones92.totum.ui.settings

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.diagnosticsNote
import com.dewijones92.totum.playback.SpeechGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SpeechBoostSection(container: AppContainer, gate: SpeechGate, onGate: (SpeechGate) -> Unit) {
    AnchoredRow(SettingsRow.SPEECH_BOOST) {
        Column {
            Text(
                text = stringResource(R.string.speech_boost_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.speech_boost_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    var feedbackOn by rememberSaveable { mutableStateOf<SpeechGate?>(null) }
    SpeechGate.entries.forEach { option ->
        GateRow(option, option == gate, onSelect = { onGate(option) }, onFeedback = { feedbackOn = option })
    }
    feedbackOn?.let { option -> FeedbackDialog(container, option) { feedbackOn = null } }
}

@Composable
private fun GateRow(gate: SpeechGate, selected: Boolean, onSelect: () -> Unit, onFeedback: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .testTag("speech-gate-${gate.name.lowercase()}")
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.weight(1f)) {
            Text(stringResource(gate.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(gate.summaryRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onFeedback) { Text(stringResource(R.string.speech_boost_feedback)) }
    }
}

@Composable
private fun FeedbackDialog(container: AppContainer, gate: SpeechGate, onDone: () -> Unit) {
    val context = LocalContext.current
    var typed by rememberSaveable { mutableStateOf("") }
    val name = stringResource(gate.labelRes())
    val sent = stringResource(R.string.speech_boost_feedback_sent)
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.speech_boost_feedback_title, name)) },
        text = {
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text(stringResource(R.string.speech_boost_feedback_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = typed.isNotBlank(), onClick = {
                val note = diagnosticsNote("💬 Feedback on the speech boost \"$name\" (${gate.name}): $typed", name)
                Diag.log("boost", "feedback on speech gate ${gate.name} sent (${typed.length} chars)")
                onDone()
                container.applicationScope.launch {
                    withContext(Dispatchers.IO) { container.sendDiagnostics(note) }
                    Toast.makeText(context, sent, Toast.LENGTH_SHORT).show()
                }
            }) { Text(stringResource(R.string.speech_boost_feedback_send)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

@StringRes
private fun SpeechGate.labelRes(): Int = when (this) {
    SpeechGate.OFF -> R.string.speech_boost_off
    SpeechGate.MEASURE -> R.string.speech_boost_measure
    SpeechGate.LIFT -> R.string.speech_boost_lift
}

@StringRes
private fun SpeechGate.summaryRes(): Int = when (this) {
    SpeechGate.OFF -> R.string.speech_boost_off_summary
    SpeechGate.MEASURE -> R.string.speech_boost_measure_summary
    SpeechGate.LIFT -> R.string.speech_boost_lift_summary
}
