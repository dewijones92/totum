package com.dewijones92.totum.ui.importexport

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.importexport.ImportSummary
import com.dewijones92.totum.ui.importexport.ImportExportViewModel.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bring subscriptions in from other apps (OPML / NewPipe / Takeout) and write
 * them back out as OPML. File reading/writing is done here against the content
 * resolver; parsing and applying live behind the view model.
 */
@Composable
fun ImportExportScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val messages = ImportExportViewModel.Messages(
        unreadable = stringResource(R.string.backup_failed),
        tooNew = stringResource(R.string.backup_too_new),
        restored = LocalContext.current.let { context ->
            {
                    subs, lists, positions ->
                context.getString(R.string.backup_restored, subs, lists, positions)
            }
        },
        restoring = stringResource(R.string.backup_restoring),
        backingUp = stringResource(R.string.backup_making),
        exporting = stringResource(R.string.export_working),
    )
    val viewModel: ImportExportViewModel =
        viewModel(factory = ImportExportViewModel.factory(container, messages))
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    var exportMessage by remember { mutableStateOf<String?>(null) }
    val exportDone = stringResource(R.string.import_export_export_done)
    val exportFailed = stringResource(R.string.import_export_export_failed)

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) { readText(resolver, uri) }
            if (content != null) viewModel.import(content)
        }
    }
    // octet-stream (not application/xml) so the picker keeps the ".opml" name
    // instead of appending ".xml" to it.
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val opml = viewModel.buildOpml()
            val ok = withContext(Dispatchers.IO) { writeText(resolver, uri, opml) }
            exportMessage = if (ok) exportDone else exportFailed
        }
    }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            Header(onBack)
            ImportSection(signedIn) {
                exportMessage = null
                viewModel.clearResult()
                importPicker.launch(arrayOf("*/*"))
            }
            ImportResult(state)
            Spacer(Modifier.height(24.dp))
            ExportSection { exportPicker.launch(EXPORT_FILE_NAME) }
            exportMessage?.let { StatusText(it) }
            Spacer(Modifier.height(24.dp))
            BackupControls(viewModel, resolver, scope)
        }
    }
}

/**
 * The backup pair with its own file pickers and status, so the screen above stays about
 * layout rather than about two more activity results.
 */
@Composable
private fun BackupControls(
    viewModel: ImportExportViewModel,
    resolver: android.content.ContentResolver,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var message by remember { mutableStateOf<String?>(null) }
    val written = stringResource(R.string.backup_written)
    val failed = stringResource(R.string.backup_failed)
    val backupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val content = viewModel.buildBackup()
            message = if (withContext(Dispatchers.IO) { writeText(resolver, uri, content) }) written else failed
        }
    }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val content = withContext(Dispatchers.IO) { readText(resolver, uri) }
            message = if (content == null) failed else viewModel.restore(content)
        }
    }
    BackupSection(
        onBackUp = { backupPicker.launch(BACKUP_FILE_NAME) },
        onRestore = { restorePicker.launch(arrayOf("*/*")) },
    )
    message?.let { StatusText(it) }
}

/**
 * Full backup and restore, alongside the OPML pair. Kept visibly separate because they
 * are not alternatives: OPML moves subscriptions to *another app*, a backup moves this
 * app's whole state to *another device*.
 */
@Composable
private fun BackupSection(onBackUp: () -> Unit, onRestore: () -> Unit) {
    SectionTitle(stringResource(R.string.backup_section))
    Text(
        text = stringResource(R.string.backup_supporting),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Row(modifier = Modifier.padding(16.dp)) {
        Button(onClick = onBackUp) { Text(stringResource(R.string.backup_create)) }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = onRestore) { Text(stringResource(R.string.backup_restore)) }
    }
}

@Composable
private fun Header(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Text(
            text = stringResource(R.string.import_export_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ImportSection(signedIn: Boolean, onPick: () -> Unit) {
    SectionTitle(stringResource(R.string.import_export_import_section))
    BodyText(stringResource(R.string.import_export_import_desc))
    if (!signedIn) BodyText(stringResource(R.string.import_export_signed_out_hint))
    Button(onClick = onPick, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.import_export_import_button))
    }
}

@Composable
private fun ExportSection(onExport: () -> Unit) {
    SectionTitle(stringResource(R.string.import_export_export_section))
    BodyText(stringResource(R.string.import_export_export_desc))
    OutlinedButton(onClick = onExport, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.import_export_export_button))
    }
}

@Composable
private fun ImportResult(state: State) {
    when (state) {
        State.Idle -> Unit
        State.Working -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.height(20.dp))
            Text(stringResource(R.string.import_export_working), modifier = Modifier.padding(start = 12.dp))
        }
        is State.Imported -> StatusText(summaryText(state.summary))
        is State.Failed -> StatusText(state.detail)
    }
}

@Composable
private fun summaryText(summary: ImportSummary): String = buildList {
    add(stringResource(R.string.import_export_result_added, summary.podcastsAdded, summary.channelsAdded))
    if (summary.alreadyPresent > 0) add(stringResource(R.string.import_export_result_already, summary.alreadyPresent))
    if (summary.channelsSkippedSignedOut > 0) {
        add(stringResource(R.string.import_export_result_skipped, summary.channelsSkippedSignedOut))
    }
    if (summary.failed > 0) add(stringResource(R.string.import_export_result_failed, summary.failed))
}.joinToString("\n")

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun BodyText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private const val BACKUP_FILE_NAME = "totum-backup.json"

private const val EXPORT_FILE_NAME = "subscriptions.opml"

private fun readText(resolver: ContentResolver, uri: Uri): String? {
    val stream = runCatching { resolver.openInputStream(uri) }.getOrNull() ?: return null
    return runCatching { stream.use { it.readBytes().decodeToString() } }.getOrNull()
}

private fun writeText(resolver: ContentResolver, uri: Uri, text: String): Boolean {
    val stream = runCatching { resolver.openOutputStream(uri) }.getOrNull() ?: return false
    return runCatching {
        stream.use { it.write(text.toByteArray()) }
        true
    }.getOrDefault(false)
}
