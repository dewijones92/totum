package com.dewijones92.totum.bedtime

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra
import com.dewijones92.totum.reminders.BedtimeCodec

internal fun BackupService.BackupSettings.withBedtime(bedtime: Bedtime): BackupService.BackupSettings =
    withExtra(
        key = KEY,
        tag = Bedtime.TAG,
        what = "bedtime settings",
        read = { BedtimeCodec.encode(bedtime.settings.value) },
        apply = { text -> bedtime.update("backup restore") { BedtimeCodec.decode(text) } },
    )

private const val KEY = "bedtime.v1"
