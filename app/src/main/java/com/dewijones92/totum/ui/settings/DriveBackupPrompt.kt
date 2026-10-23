package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.backup.drive.DriveBackup
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.diagnostics.runningUnderInstrumentation
import com.dewijones92.totum.stars.observeYourPlaylists
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private enum class Ask { NOTHING, BACK_UP, RESTORE }

@Composable
fun DriveBackupPrompt(container: AppContainer) {
    val drive = container.driveBackup
    var ask by rememberSaveable { mutableStateOf<Ask?>(null) }
    LaunchedEffect(Unit) {
        if (ask != null) return@LaunchedEffect
        ask = when {
            runningUnderInstrumentation() -> Ask.NOTHING
            drive.isSetUp || drive.neverAsk -> Ask.NOTHING
            container.playHistoryStore.observe().first().isEmpty() &&
                container.localPlaylistStore.observeYourPlaylists().first().isEmpty() -> Ask.RESTORE
            else -> Ask.BACK_UP
        }
        Diag.log("backup", "launch prompt: $ask (setUp=${drive.isSetUp} neverAsk=${drive.neverAsk})")
    }
    val scope = rememberCoroutineScope()
    val pickers = rememberDriveLocationPickers { location ->
        val restoring = ask == Ask.RESTORE
        ask = Ask.NOTHING
        drive.chose(location)
        scope.launch { if (restoring) restoreLatest(drive) else drive.backUpNow("location chosen at launch") }
    }
    val showing = ask ?: return
    if (showing == Ask.NOTHING) return
    AskDialog(
        restore = showing == Ask.RESTORE,
        pickers = pickers,
        onNotNow = {
            ask = Ask.NOTHING
            Diag.log("backup", "launch prompt: not now")
        },
        onNever = {
            drive.neverAsk = true
            ask = Ask.NOTHING
        },
    )
}

@Composable
private fun AskDialog(restore: Boolean, pickers: DriveLocationPickers, onNotNow: () -> Unit, onNever: () -> Unit) {
    AlertDialog(
        modifier = Modifier.testTag(DRIVE_BACKUP_PROMPT_TAG),
        onDismissRequest = onNotNow,
        title = {
            val title = if (restore) R.string.drive_backup_prompt_restore_title else R.string.drive_backup_prompt_title
            Text(stringResource(title))
        },
        text = {
            val body = if (restore) R.string.drive_backup_prompt_restore_body else R.string.drive_backup_prompt_body
            Text(stringResource(body))
        },
        confirmButton = {
            Column {
                TextButton(onClick = pickers.folder) { Text(stringResource(R.string.drive_backup_choose_folder)) }
                val fileLabel =
                    if (restore) R.string.drive_backup_choose_existing_file else R.string.drive_backup_choose_file
                TextButton(onClick = if (restore) pickers.existingFile else pickers.file) {
                    Text(stringResource(fileLabel))
                }
                TextButton(onClick = onNotNow) { Text(stringResource(R.string.drive_backup_not_now)) }
                TextButton(onClick = onNever) { Text(stringResource(R.string.drive_backup_never)) }
            }
        },
    )
}

private suspend fun restoreLatest(drive: DriveBackup) {
    val latest = drive.copies().firstOrNull { it.first.name == DriveBackup.LATEST } ?: drive.copies().firstOrNull()
    if (latest == null) {
        Diag.warn("backup", "restore at launch: no backup found in the chosen folder; backing up there instead")
        drive.backUpNow("nothing to restore; first backup")
        return
    }
    drive.restore(latest.first, latest.second)
}

const val DRIVE_BACKUP_PROMPT_TAG = "drive-backup-prompt"
