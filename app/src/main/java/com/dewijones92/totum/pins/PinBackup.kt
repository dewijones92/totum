package com.dewijones92.totum.pins

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.common.Diag

internal fun BackupService.BackupSettings.withPins(pins: PinStore): BackupService.BackupSettings {
    val settings = this
    return object : BackupService.BackupSettings {
        override fun export(): Map<String, String> = settings.export() + (KEY to PinCodec.encode(pins.pins.value))

        override fun restore(values: Map<String, String>) {
            settings.restore(values)
            val saved = values[KEY]?.let { runCatching { PinCodec.decode(it) }.getOrNull() } ?: return
            val merged = (pins.pins.value + saved).distinctBy { it.key }
            Diag.log("pin", "restored ${saved.size} pin(s) from a backup; ${merged.size} now pinned")
            pins.replaceAll(merged)
        }
    }
}

internal fun BackupService.BackupSettings.withRecent(recent: RecentActions): BackupService.BackupSettings {
    val settings = this
    return object : BackupService.BackupSettings {
        override fun export(): Map<String, String> =
            settings.export() + (RECENT_KEY to PinCodec.encode(recent.recent.value))

        override fun restore(values: Map<String, String>) {
            settings.restore(values)
            val saved = values[RECENT_KEY]?.let { runCatching { PinCodec.decode(it) }.getOrNull() } ?: return
            recent.mergeMissing(saved)
        }
    }
}

private const val KEY = "pins.v1"
private const val RECENT_KEY = "recent.v1"
