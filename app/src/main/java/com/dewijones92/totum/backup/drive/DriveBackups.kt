package com.dewijones92.totum.backup.drive

import android.content.Context
import androidx.core.net.toUri
import com.dewijones92.totum.backup.BackupService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import java.io.File

fun driveBackupFor(context: Context, service: BackupService): DriveBackup {
    val resolver = context.contentResolver
    return DriveBackup(
        service = service,
        locations = SharedPrefsBackupLocationStore(context),
        open = { location ->
            val uri = location.uri.toUri()
            if (location.isFolder) SafTreeFolder(resolver, uri) else SafSingleFile(resolver, uri)
        },
        phoneCopies = LocalBackupFolder(File(context.filesDir, "drive-backup-daily")),
    )
}

fun startDriveBackups(scope: CoroutineScope, drive: DriveBackup, changes: List<Pair<String, Flow<*>>>) {
    BackupScheduler(
        scope = scope,
        backUp = { why -> if (drive.isSetUp) drive.backUpNow(why) },
        now = System::currentTimeMillis,
    ).follow(changes.map { (name, flow) -> flow.drop(1).map { name } }.merge())
}
