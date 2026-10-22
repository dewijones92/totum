package com.dewijones92.totum.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.download.LockingDownloadManager
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.launch

internal val LocalDownloadLocking = staticCompositionLocalOf<LockingDownloadManager?> { null }

@Composable
internal fun isDownloadLocked(id: MediaItemId): Boolean {
    val locking = LocalDownloadLocking.current ?: return false
    val locked by locking.locks.locked.collectAsStateWithLifecycle()
    return id in locked
}

@Composable
internal fun DownloadLockAction(id: MediaItemId?, onDismiss: () -> Unit) {
    val locking = LocalDownloadLocking.current ?: return
    id ?: return
    val locked = isDownloadLocked(id)
    SheetAction(
        { locking.setLocked(id, !locked) },
        if (locked) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
        if (locked) R.string.download_unlock else R.string.download_lock,
        onDismiss,
    )
}

@Composable
internal fun LockedDownloadAsk(locking: LockingDownloadManager) {
    val asked = remember { mutableStateListOf<MediaItemId>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(locking) { locking.refusals.collect { if (it !in asked) asked += it } }
    if (asked.isEmpty()) return
    val close = { why: String ->
        Diag.log("download", "locked-download question for ${asked.size} item(s) closed: $why")
        asked.clear()
    }
    AlertDialog(
        onDismissRequest = { close("dismissed; kept") },
        title = { Text(stringResource(R.string.download_locked_title)) },
        text = { Text(pluralStringResource(R.plurals.download_locked_body, asked.size, asked.size)) },
        confirmButton = {
            TextButton(
                onClick = {
                    val ids = asked.toList()
                    close("unlock and delete")
                    scope.launch { ids.forEach { locking.unlockAndDelete(it) } }
                },
            ) { Text(stringResource(R.string.download_unlock_and_delete)) }
        },
        dismissButton = {
            TextButton(
                onClick = { close("kept") }
            ) { Text(stringResource(R.string.download_keep_locked)) }
        },
    )
}
