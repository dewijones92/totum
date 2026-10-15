package com.dewijones92.totum.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.dewijones92.totum.backup.drive.BackupLocation
import com.dewijones92.totum.backup.drive.DriveBackup
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.launch

class DriveLocationPickers(val folder: () -> Unit, val file: () -> Unit, val existingFile: () -> Unit)

@Composable
fun rememberDriveLocationPickers(onChosen: (BackupLocation) -> Unit): DriveLocationPickers {
    val context = LocalContext.current
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { onChosen(kept(context, it, isFolder = true)) } ?: Diag.log("backup", "no Drive folder chosen")
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { onChosen(kept(context, it, isFolder = false)) } ?: Diag.log("backup", "no Drive file chosen")
    }
    val existing = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onChosen(kept(context, it, isFolder = false)) } ?: Diag.log("backup", "no backup file chosen")
    }
    return remember {
        DriveLocationPickers(
            folder = { tree.launch(null) },
            file = { file.launch(DriveBackup.LATEST) },
            existingFile = { existing.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) },
        )
    }
}

private fun kept(context: Context, uri: Uri, isFolder: Boolean): BackupLocation {
    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        .onFailure { Diag.warn("backup", "could not keep access to $uri", it) }
    return BackupLocation(uri.toString(), isFolder)
}
