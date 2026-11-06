package com.dewijones92.totum.notforme

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.notforme.NotForMe

internal fun BackupService.BackupSettings.withNotForMe(notForMe: NotForMe): BackupService.BackupSettings {
    val settings = this
    return object : BackupService.BackupSettings {
        override fun export(): Map<String, String> = settings.export() + (KEY to notForMe.encode())

        override fun restore(values: Map<String, String>) {
            settings.restore(values)
            val saved = values[KEY]?.let {
                runCatching { NotForMe.decode(it) }
                    .onFailure { Diag.warn("notforme", "dewidebug the backup's not-for-me list is unreadable: $it") }
                    .getOrNull()
            } ?: return
            notForMe.mergeMissing(saved)
        }
    }
}

private const val KEY = "notforme.v1"
