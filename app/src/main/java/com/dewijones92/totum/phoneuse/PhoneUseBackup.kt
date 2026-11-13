package com.dewijones92.totum.phoneuse

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra
import com.dewijones92.totum.stats.UnlockNudgeCodec

internal fun BackupService.BackupSettings.withPhoneUse(phoneUse: PhoneUse): BackupService.BackupSettings =
    withExtra(
        key = KEY,
        tag = PhoneUse.TAG,
        what = "phone use settings",
        read = { UnlockNudgeCodec.encode(phoneUse.settings.value) },
        apply = { text -> phoneUse.update("backup restore") { UnlockNudgeCodec.decode(text) } },
    )

private const val KEY = "phone-use.v1"
