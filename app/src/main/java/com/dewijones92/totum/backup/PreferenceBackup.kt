package com.dewijones92.totum.backup

import com.dewijones92.totum.data.sponsorblock.SkipCategory
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.PlaybackMode

/**
 * Settings as flat strings for a backup, and back again.
 *
 * Written out by hand rather than reflected over [AppPreferences.Settings], so adding a
 * setting is a deliberate decision about whether it belongs in a backup — a device-shaped
 * preference (say a per-network quality cap) may well not belong on a different phone.
 * An unreadable or unknown value is skipped, leaving that setting at its current value.
 */
internal fun AppPreferences.asBackupSettings(): BackupService.BackupSettings =
    object : BackupService.BackupSettings {

        override fun export(): Map<String, String> = with(settings.value) {
            mapOf(
                KEY_AUTOPLAY to autoPlayNext.toString(),
                KEY_AUTO_DOWNLOAD to autoDownloadQueue.toString(),
                KEY_AUTO_DOWNLOAD_WIFI to autoDownloadWifiOnly.toString(),
                KEY_PLAYBACK_MODE to playbackMode.name,
                KEY_SKIP to skipCategories.joinToString(",") { it.id },
                KEY_WIFI_MAX to wifiMaxHeight.toString(),
                KEY_CELLULAR_MAX to cellularMaxHeight.toString(),
                KEY_FEED_HIDDEN to feedHiddenUntilAsked.toString(),
            )
        }

        override fun restore(values: Map<String, String>) {
            values[KEY_AUTOPLAY]?.toBooleanStrictOrNull()?.let(::setAutoPlayNext)
            values[KEY_AUTO_DOWNLOAD]?.toBooleanStrictOrNull()?.let(::setAutoDownloadQueue)
            values[KEY_AUTO_DOWNLOAD_WIFI]?.toBooleanStrictOrNull()?.let(::setAutoDownloadWifiOnly)
            values[KEY_PLAYBACK_MODE]
                ?.let { name -> runCatching { PlaybackMode.valueOf(name) }.getOrNull() }
                ?.let(::setPlaybackMode)
            values[KEY_SKIP]?.let { raw ->
                setSkipCategories(raw.split(",").mapNotNullTo(mutableSetOf()) { SkipCategory.fromId(it.trim()) })
            }
            values[KEY_WIFI_MAX]?.toIntOrNull()?.let(::setWifiMaxHeight)
            values[KEY_CELLULAR_MAX]?.toIntOrNull()?.let(::setCellularMaxHeight)
            values[KEY_FEED_HIDDEN]?.toBooleanStrictOrNull()?.let(::setFeedHiddenUntilAsked)
        }
    }

private const val KEY_AUTOPLAY = "autoPlayNext"
private const val KEY_AUTO_DOWNLOAD = "autoDownloadQueue"
private const val KEY_AUTO_DOWNLOAD_WIFI = "autoDownloadWifiOnly"
private const val KEY_PLAYBACK_MODE = "playbackMode"
private const val KEY_SKIP = "skipCategories"
private const val KEY_WIFI_MAX = "wifiMaxHeight"
private const val KEY_CELLULAR_MAX = "cellularMaxHeight"
private const val KEY_FEED_HIDDEN = "feedHiddenUntilAsked"
