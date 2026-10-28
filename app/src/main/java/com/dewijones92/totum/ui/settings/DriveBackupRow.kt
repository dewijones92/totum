package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.drive.BackupCopy
import com.dewijones92.totum.backup.drive.BackupFolder
import com.dewijones92.totum.backup.drive.DriveBackup
import com.dewijones92.totum.backup.drive.DriveBackupStatus
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DriveBackupRow(drive: DriveBackup, operations: Operations? = null) {
    val status by drive.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val backingUp = stringResource(R.string.drive_backup_working)
    val backUp: (String) -> Unit = { why ->
        if (operations?.isRunning(DRIVE_BACKUP_KEY) != true) {
            scope.launch {
                operations.tracking(DRIVE_BACKUP_KEY, OperationKind.TASK, backingUp) {
                    if (drive.backUpNow(why)) OperationEnd.Done(1) else OperationEnd.Failed(why)
                }
            }
        }
    }
    var restoring by remember { mutableStateOf(false) }
    var restored by remember { mutableStateOf<String?>(null) }
    val pickers = rememberDriveLocationPickers { location ->
        drive.chose(location)
        backUp("location chosen in Settings")
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(DRIVE_BACKUP_TAG)) {
        Text(stringResource(R.string.drive_backup_title), style = MaterialTheme.typography.bodyLarge)
        Text(
            statusText(status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        restored?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        FlowRow {
            if (status is DriveBackupStatus.NotSetUp) {
                TextButton(onClick = pickers.folder) { Text(stringResource(R.string.drive_backup_choose_folder)) }
                TextButton(onClick = pickers.file) { Text(stringResource(R.string.drive_backup_choose_file)) }
            } else {
                TextButton(onClick = { backUp("Back up now") }) {
                    Text(stringResource(R.string.drive_backup_now))
                }
                TextButton(onClick = { restoring = true }) { Text(stringResource(R.string.drive_backup_restore)) }
                TextButton(onClick = drive::turnOff) { Text(stringResource(R.string.drive_backup_turn_off)) }
            }
        }
    }
    if (restoring) {
        RestoreDialog(drive, operations, onDone = { summary ->
            restoring = false
            restored = summary
        })
    }
}

@Composable
private fun RestoreDialog(drive: DriveBackup, operations: Operations?, onDone: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    val restoringText = stringResource(R.string.drive_backup_restoring)
    var copies by remember { mutableStateOf<List<Pair<BackupCopy, BackupFolder>>?>(null) }
    val restoredText = stringResource(R.string.drive_backup_restored)
    val failedText = stringResource(R.string.drive_backup_restore_failed)
    LaunchedEffect(Unit) { copies = drive.copies() }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(stringResource(R.string.drive_backup_restore_title)) },
        text = {
            Column {
                Text(stringResource(R.string.drive_backup_restore_merges), style = MaterialTheme.typography.bodySmall)
                when (val list = copies) {
                    null -> Text(stringResource(R.string.drive_backup_loading))
                    emptyList<Pair<BackupCopy, BackupFolder>>() -> Text(stringResource(R.string.drive_backup_no_copies))
                    else -> list.forEach { (copy, folder) ->
                        Text(
                            "${copy.name} · ${clock(copy.modifiedAtMs, "d MMM HH:mm")} · ${folder.where}",
                            modifier = Modifier.fillMaxWidth().clickable(
                                enabled = operations?.isRunning(DRIVE_RESTORE_KEY) != true,
                            ) {
                                scope.launch {
                                    var summary: BackupService.RestoreSummary? = null
                                    operations.tracking(DRIVE_RESTORE_KEY, OperationKind.TASK, restoringText) {
                                        summary = drive.restore(copy, folder)
                                        if (summary != null) OperationEnd.Done(1) else OperationEnd.Failed(failedText)
                                    }
                                    onDone(summary?.let { String.format(restoredText, it.readable()) } ?: failedText)
                                }
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun statusText(status: DriveBackupStatus): String = when (status) {
    DriveBackupStatus.NotSetUp -> stringResource(R.string.drive_backup_not_set_up)
    DriveBackupStatus.Waiting -> stringResource(R.string.drive_backup_waiting)
    is DriveBackupStatus.Done -> stringResource(R.string.drive_backup_done, clock(status.atMs, "HH:mm"), status.where)
    is DriveBackupStatus.Failed -> stringResource(
        R.string.drive_backup_failed,
        clock(status.atMs, "HH:mm"),
        status.reason
    )
}

private fun clock(epochMs: Long, pattern: String): String =
    DateTimeFormatter.ofPattern(pattern).format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

const val DRIVE_BACKUP_TAG = "drive-backup"

internal fun BackupService.RestoreSummary.readable(): String =
    "$subscriptions subscriptions, $playlists playlists, $groupMembers group members, " +
        "$history history items, $progressEntries positions"

private const val DRIVE_BACKUP_KEY = "drive-backup"
private const val DRIVE_RESTORE_KEY = "drive-restore"
