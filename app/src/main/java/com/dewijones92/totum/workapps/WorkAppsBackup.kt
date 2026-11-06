package com.dewijones92.totum.workapps

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.withExtra
import com.dewijones92.totum.dailyalarms.WorkAppsCodec
import com.dewijones92.totum.dailyalarms.WorkAppsSettings

internal fun mergedWorkApps(phone: WorkAppsSettings, saved: WorkAppsSettings): WorkAppsSettings {
    val factory = WorkAppsSettings()
    val kept = phone.groups.map { mine ->
        val theirs = saved.group(mine.id)
        if (theirs != null && mine == factory.group(mine.id)) theirs.copy(pausedUntil = null) else mine
    }
    val added = saved.groups.filter { phone.group(it.id) == null }.map { it.copy(pausedUntil = null) }
    return phone.copy(enabled = phone.enabled || saved.enabled, groups = kept + added)
}

internal fun BackupService.BackupSettings.withWorkApps(workApps: WorkAppsQuiet): BackupService.BackupSettings =
    withExtra(
        key = KEY,
        tag = WorkAppsQuiet.TAG,
        what = "work-app quiet groups",
        read = {
            val settings = workApps.settings.value
            WorkAppsCodec.encode(settings.copy(groups = settings.groups.map { it.copy(pausedUntil = null) }))
        },
        apply = { text ->
            val saved = WorkAppsCodec.decode(text)
            workApps.update("backup restore") { phone -> mergedWorkApps(phone, saved) }
        },
    )

private const val KEY = "workapps.v1"
