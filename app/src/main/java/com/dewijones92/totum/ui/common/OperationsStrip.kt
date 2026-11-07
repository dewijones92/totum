package com.dewijones92.totum.ui.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.TransferPlace
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
            stripLines(running).forEach { line ->
                when (line) {
                    is StripLine.Single -> OperationRow(line.operation, onCancel)
                    is StripLine.Group -> GroupRow(line, onCancel)
                }
            }
        }
    }
}

sealed interface StripLine {
    data class Single(val operation: Operation) : StripLine
    data class Group(val kind: OperationKind, val operations: List<Operation>) : StripLine {
        val percent: Int?
            get() = operations.mapNotNull { it.percentDone() }.takeIf { it.isNotEmpty() }?.average()?.toInt()
    }
}

private fun Operation.percentDone(): Int? {
    val done = step ?: return null
    val total = steps?.takeIf { it > 0 } ?: return null
    return done * PERCENT / total
}

fun stripLines(running: List<Operation>): List<StripLine> =
    running.groupBy { it.kind }.values.map { same ->
        if (same.size >= GROUP_FROM) StripLine.Group(same.first().kind, same) else StripLine.Single(same.single())
    }

@Composable
private fun GroupRow(group: StripLine.Group, onCancel: (Long) -> Unit) {
    var open by rememberSaveable(group.kind) { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { open = !open }) {
                Text(
                    pluralStringResource(
                        R.plurals.ops_group,
                        group.operations.size,
                        groupTitle(group.kind),
                        group.operations.size
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(if (open) R.string.ops_group_hide else R.string.ops_group_show),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            TextButton(onClick = { group.operations.forEach { onCancel(it.id) } }) {
                Text(stringResource(R.string.ops_cancel_all))
            }
        }
        val percent = group.percent
        if (percent != null) {
            LinearProgressIndicator(
                progress = { percent / PERCENT.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp, end = 12.dp))
        }
        if (open) group.operations.forEach { OperationRow(it, onCancel) }
    }
}

@Composable
private fun groupTitle(kind: OperationKind): String = when (kind) {
    OperationKind.HOME_DOWNLOAD -> transferRoute(LocalResources.current, sendingToPi(TransferPlace.WEB))
    OperationKind.HOME_UPLOAD -> transferRoute(LocalResources.current, sendingToPi(TransferPlace.PHONE))
    OperationKind.PREPARE_TORRENT -> stringResource(R.string.ops_group_torrents)
    OperationKind.DOWNLOAD_BATCH -> stringResource(R.string.ops_group_downloads)
    OperationKind.ASK -> stringResource(R.string.ops_group_asks)
    else -> stringResource(R.string.ops_group_tasks)
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
    OperationKind.ASK -> stringResource(R.string.ops_ask, operation.subject)
    OperationKind.HOME_DOWNLOAD ->
        transferTitled(LocalResources.current, sendingToPi(TransferPlace.WEB), operation.subject)
    OperationKind.HOME_UPLOAD ->
        transferTitled(LocalResources.current, sendingToPi(TransferPlace.PHONE), operation.subject)
    OperationKind.SEND_TO_TARGET -> stringResource(R.string.ops_send_to_target, operation.subject)
    OperationKind.WAKE_TARGET -> stringResource(R.string.ops_wake_target, operation.subject)
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
            OperationKind.HOME_DOWNLOAD, OperationKind.HOME_UPLOAD -> add(
                stringResource(R.string.ops_home_percent, step)
            )
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
    is OperationEnd.Done -> end.note?.let { "$it\n" }.orEmpty() + context.resources.getQuantityString(
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
    OperationKind.ASK -> R.plurals.ops_ask_done
    OperationKind.HOME_DOWNLOAD, OperationKind.HOME_UPLOAD -> R.plurals.ops_home_done
    OperationKind.SEND_TO_TARGET -> R.plurals.ops_send_done
    OperationKind.WAKE_TARGET -> R.plurals.ops_wake_done
}

private const val SHOW_AFTER_MS = 1_000L
private const val GROUP_FROM = 2
private const val PERCENT = 100
private const val SHOW_TICK_MS = 500L
