package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.GitHubReport
import com.dewijones92.totum.common.HomeServerAuth
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.GitHubReportSources
import com.dewijones92.totum.diagnostics.OpenedReport
import com.dewijones92.totum.diagnostics.OpenedReports
import com.dewijones92.totum.diagnostics.reportRedactorFor
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.ui.common.rememberLinkOpener
import com.dewijones92.totum.ui.common.rememberSuspendCopier
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun AppPreferences.Settings.signedInToHome(): Boolean =
    HomeServerAuth.isSignedIn(homeServerBase, homeServerToken)

@Composable
internal fun ReportDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onSendToPi: (String, List<EncodedScreenshot>) -> Unit,
) {
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    if (settings.signedInToHome()) {
        DiagnosticsNoteDialog(onDismiss = onDismiss, onSend = onSendToPi)
    } else {
        GitHubReportDialog(settings, onDismiss)
    }
}

@Composable
private fun GitHubReportDialog(settings: AppPreferences.Settings, onDismiss: () -> Unit) {
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    var draftNote by rememberSaveable { mutableStateOf("") }
    val typed = note
    val context = LocalContext.current
    if (typed == null) {
        val earlier = remember { OpenedReports(context).recent() }
        DiagnosticsNoteDialog(
            onDismiss = onDismiss,
            onSend = { text, _ ->
                Diag.log("diagnostics", "GitHub report: ${text.trim().length} chars typed; showing the preview")
                draftNote = text
                note = text
            },
            supporting = R.string.report_github_note_supporting,
            confirm = R.string.report_github_preview_button,
            withScreenshots = false,
            initial = draftNote,
            extra = { EarlierReports(earlier) },
        )
    } else {
        GitHubPreviewDialog(settings, typed, onBack = { note = null }, onDone = onDismiss)
    }
}

@Composable
private fun ColumnScope.EarlierReports(earlier: List<OpenedReport>) {
    val opener = rememberLinkOpener()
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    if (earlier.isNotEmpty()) {
        Text(
            text = pluralStringResource(R.plurals.report_github_earlier, earlier.size, earlier.size),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp),
        )
        earlier.take(EARLIER_SHOWN).forEach { report ->
            val day = dates.format(Instant.ofEpochMilli(report.atEpochMs).atZone(ZoneId.systemDefault()))
            Text(
                text = stringResource(R.string.report_github_earlier_line, day, report.title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    TextButton(onClick = { opener(GitHubReport.MY_ISSUES_URL) }) {
        Text(stringResource(R.string.report_github_mine))
    }
}

@Composable
private fun GitHubPreviewDialog(
    settings: AppPreferences.Settings,
    note: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var withEvents by rememberSaveable { mutableStateOf(false) }
    val redactor = remember(settings.homeServerBase, settings.homeServerToken, settings.prowlarrApiKey) {
        reportRedactorFor(settings.homeServerBase, settings.homeServerToken, settings.prowlarrApiKey)
    }
    val facts = remember { GitHubReportSources.facts() }
    val events = remember { GitHubReportSources.events() }
    val draft = remember(note, withEvents, redactor) {
        GitHubReport.draft(note, facts, if (withEvents) events else emptyList(), redactor)
    }
    val copy = rememberSuspendCopier()
    val open = rememberLinkOpener()
    val what = stringResource(R.string.report_github_what)
    AlertDialog(
        onDismissRequest = onBack,
        title = { Text(stringResource(R.string.report_github_preview_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(R.string.report_github_preview_supporting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EventsSwitch(withEvents) { withEvents = it }
                if (withEvents) EventsStatus(draft.eventsKept, draft.eventsOffered)
                if (draft.noteCut) {
                    Text(
                        text = stringResource(R.string.report_github_note_cut),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = draft.body,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp).testTag("github-report-body"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                Diag.log(
                    "diagnostics",
                    "GitHub report: copying and opening it; title ${draft.title.length} chars, body " +
                        "${draft.body.length} chars, link ${draft.url.length} chars, activity lines " +
                        "${draft.eventsKept} of ${draft.eventsOffered} (asked for=$withEvents), " +
                        "note cut=${draft.noteCut}",
                )
                scope.launch {
                    copy(draft.body, what)
                    OpenedReports(context).record(draft.title)
                    open(draft.url)
                    onDone()
                }
            }) { Text(stringResource(R.string.report_github_open)) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.report_github_back)) }
        },
    )
}

@Composable
private fun EventsSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.report_github_events_title), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.report_github_events_summary),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun EventsStatus(kept: Int, offered: Int) {
    val text = if (kept < offered) {
        pluralStringResource(R.plurals.report_github_events_trimmed, kept, kept, offered)
    } else {
        pluralStringResource(R.plurals.report_github_events_kept, kept, kept)
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

private const val EARLIER_SHOWN = 3
