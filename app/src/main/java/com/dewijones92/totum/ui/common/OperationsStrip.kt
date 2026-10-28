package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.operations.Ended
import com.dewijones92.totum.operations.Operation
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import kotlinx.coroutines.delay

const val OPERATIONS_STRIP_TAG = "operations-strip"

@Composable
fun OperationsStrip(running: List<Operation>, padStatusBar: Boolean, onCancel: (Long) -> Unit) {
    if (running.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(OPERATIONS_STRIP_TAG),
    ) {
        Column(if (padStatusBar) Modifier.windowInsetsPadding(WindowInsets.statusBars) else Modifier) {
            running.forEach { operation -> OperationRow(operation, onCancel) }
        }
    }
}

@Composable
private fun OperationRow(operation: Operation, onCancel: (Long) -> Unit) {
    val title = titleOf(operation)
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                detailOf(operation).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            val cancelWhat = stringResource(R.string.ops_cancel_what, operation.subject)
            TextButton(
                onClick = { onCancel(operation.id) },
                modifier = Modifier.semantics { contentDescription = cancelWhat },
            ) { Text(stringResource(R.string.ops_cancel)) }
        }
        val step = operation.step
        val steps = operation.steps
        if (step != null && steps != null && steps > 0) {
            LinearProgressIndicator(
                progress = { step.toFloat() / steps },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
        }
    }
}

@Composable
fun rememberLongRunning(all: List<Operation>, clock: () -> Long = System::currentTimeMillis): List<Operation> {
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(all.isEmpty()) {
        while (all.isNotEmpty()) {
            now = clock()
            delay(SHOW_TICK_MS)
        }
    }
    return shownAt(all, now)
}

internal fun shownAt(all: List<Operation>, nowMs: Long): List<Operation> =
    all.filter { nowMs - it.startedAtMs >= SHOW_AFTER_MS }

@Composable
private fun titleOf(operation: Operation): String = when (operation.kind) {
    OperationKind.SHUFFLE_ARTIST -> stringResource(R.string.ops_shuffle_artist, operation.subject)
    OperationKind.SHUFFLE_SOURCES -> stringResource(R.string.ops_shuffle_sources, operation.subject)
    OperationKind.OPEN_PAGE -> stringResource(R.string.ops_open_page, operation.subject)
    OperationKind.PREPARE_TORRENT -> stringResource(R.string.ops_prepare_torrent, operation.subject)
    OperationKind.DOWNLOAD_BATCH -> stringResource(R.string.ops_download_batch, operation.subject)
    OperationKind.TASK -> stringResource(R.string.ops_task, operation.subject)
}

@Composable
private fun detailOf(operation: Operation): List<String> = buildList {
    if (operation.playing) add(stringResource(R.string.ops_playing))
    if (operation.found > 0) add(pluralStringResource(R.plurals.ops_found, operation.found, operation.found))
    val step = operation.step
    val steps = operation.steps
    if (step != null && steps != null) {
        when (operation.kind) {
            OperationKind.SHUFFLE_ARTIST -> add(pluralStringResource(R.plurals.ops_step_albums, steps, step, steps))
            OperationKind.DOWNLOAD_BATCH -> add(pluralStringResource(R.plurals.ops_step_downloads, steps, step, steps))
            else -> Unit
        }
    }
}

@Composable
fun OperationResultToasts(operations: Operations) {
    val context = LocalContext.current.applicationContext
    LaunchedEffect(operations) {
        operations.ended.collect { ended ->
            if (ended.kind.toastsResult) Toast.makeText(context, resultText(context, ended), Toast.LENGTH_LONG).show()
        }
    }
}

fun resultText(context: Context, ended: Ended): String = when (val end = ended.end) {
    is OperationEnd.Done -> context.resources.getQuantityString(
        doneText(ended.kind, end.partial),
        end.count,
        end.count,
        ended.subject,
    )
    OperationEnd.Nothing -> context.getString(R.string.ops_nothing, ended.subject)
    is OperationEnd.Failed -> context.getString(R.string.ops_failed, ended.subject, end.why)
    is OperationEnd.Cancelled ->
        context.getString(if (end.playing) R.string.ops_cancelled_playing else R.string.ops_cancelled, ended.subject)
}

private fun doneText(kind: OperationKind, partial: Boolean): Int = when (kind) {
    OperationKind.SHUFFLE_ARTIST, OperationKind.SHUFFLE_SOURCES, OperationKind.OPEN_PAGE, OperationKind.TASK ->
        if (partial) R.plurals.ops_done_partial else R.plurals.ops_done
    OperationKind.PREPARE_TORRENT -> R.plurals.ops_torrent_ready
    OperationKind.DOWNLOAD_BATCH -> if (partial) R.plurals.ops_downloaded_partial else R.plurals.ops_downloaded
}

private const val SHOW_AFTER_MS = 1_000L
private const val SHOW_TICK_MS = 500L
