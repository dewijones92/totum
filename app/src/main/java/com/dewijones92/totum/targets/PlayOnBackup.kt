package com.dewijones92.totum.targets

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra

internal fun BackupService.BackupSettings.withPlayOn(targets: () -> TargetsRepository): BackupService.BackupSettings =
    withPlayOnOf(read = { targets().lastUsed }, apply = { id -> targets().restoreLastUsed(id) })

internal fun BackupService.BackupSettings.withPlayOnOf(
    read: () -> String?,
    apply: (String?) -> Unit,
): BackupService.BackupSettings = withExtra(
    key = BACKUP_KEY,
    tag = "targets",
    what = "the last Play on… device",
    read = read,
    apply = { text -> apply(text) },
)

private const val BACKUP_KEY = "play-on.v1"
