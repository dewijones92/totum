package com.dewijones92.totum.backup

import com.dewijones92.totum.common.Diag

internal fun BackupService.BackupSettings.withExtra(
    key: String,
    tag: String,
    what: String,
    read: () -> String?,
    apply: (String) -> Unit,
): BackupService.BackupSettings {
    val settings = this
    return object : BackupService.BackupSettings {
        override fun export(): Map<String, String> =
            settings.export() + listOfNotNull(read()?.let { key to it })

        override fun restore(values: Map<String, String>) {
            settings.restore(values)
            val saved = values[key]
            if (saved == null) {
                Diag.log(tag, "dewidebug backup restore: this backup carries no $what")
                return
            }
            runCatching { apply(saved) }
                .onSuccess { Diag.log(tag, "dewidebug backup restore: $what restored from key $key") }
                .onFailure { Diag.warn(tag, "dewidebug backup restore: the backup's $what is unreadable", it) }
        }
    }
}
