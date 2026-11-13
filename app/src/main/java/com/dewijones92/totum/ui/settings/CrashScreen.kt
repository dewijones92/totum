package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.CrashRecord
import com.dewijones92.totum.diagnostics.CrashRecords
import com.dewijones92.totum.diagnostics.reportRedactorFor
import com.dewijones92.totum.ui.common.rememberSuspendCopier
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun CrashScreen(record: CrashRecord, container: AppContainer, onContinue: () -> Unit) {
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    var reporting by rememberSaveable { mutableStateOf(false) }
    var piStatus by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val copy = rememberSuspendCopier()
    val copyWhat = stringResource(R.string.crash_copy_what)
    val redactor = remember(settings.homeServerBase, settings.homeServerToken, settings.prowlarrApiKey) {
        reportRedactorFor(settings.homeServerBase, settings.homeServerToken, settings.prowlarrApiKey)
    }
    val sending = stringResource(R.string.crash_sending)
    val sent = stringResource(R.string.crash_sent)
    val kept = stringResource(R.string.crash_kept)
    val note = remember(record) { CrashRecords.noteFor(record) }
    val shownTrace = remember(record, redactor) { redactor.redact(record.stackTrace.trim()) }
    val whenText = remember(record) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
            .format(Instant.ofEpochMilli(record.atEpochMs).atZone(ZoneId.systemDefault()))
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .testTag("crash-screen"),
        ) {
            Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.crash_summary), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.crash_when, whenText, record.thread),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = redactor.redact(note.removePrefix("Totum crashed: ")),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("crash-message"),
            )
            Button(
                onClick = {
                    Diag.log("diagnostics", "crash screen: Report on GitHub tapped (${record.exception})")
                    reporting = true
                },
                modifier = Modifier.fillMaxWidth().testTag("crash-github"),
            ) { Text(stringResource(R.string.report_github_row)) }
            OutlinedButton(
                onClick = {
                    Diag.log("diagnostics", "crash screen: Copy tapped, ${shownTrace.length} chars of stack trace")
                    scope.launch { copy(redactor.redact(note) + "\n\n" + shownTrace, copyWhat) }
                },
                modifier = Modifier.fillMaxWidth().testTag("crash-copy"),
            ) { Text(stringResource(R.string.crash_copy)) }
            if (settings.signedInToHome()) {
                OutlinedButton(
                    enabled = piStatus != sending,
                    onClick = {
                        Diag.log("diagnostics", "crash screen: Send to my Pi tapped")
                        piStatus = sending
                        container.sendPendingReportsToPi { outcome ->
                            piStatus = if (outcome.kept == 0 && outcome.sent > 0) sent else kept
                            Diag.log("diagnostics", "crash screen: send to my Pi finished: $outcome")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("crash-pi"),
                ) { Text(stringResource(R.string.crash_send_pi)) }
                piStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            TextButton(
                onClick = {
                    Diag.log("diagnostics", "crash screen: Continue tapped")
                    onContinue()
                },
                modifier = Modifier.fillMaxWidth().testTag("crash-continue"),
            ) { Text(stringResource(R.string.crash_continue)) }
            Text(stringResource(R.string.crash_trace_heading), style = MaterialTheme.typography.titleSmall)
            SelectionContainer {
                Text(
                    text = shownTrace,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("crash-trace"),
                )
            }
        }
    }

    if (reporting) {
        GitHubPreviewDialog(
            settings = settings,
            note = note,
            onBack = { reporting = false },
            onDone = { reporting = false },
            trace = record.stackTrace,
        )
    }
}
