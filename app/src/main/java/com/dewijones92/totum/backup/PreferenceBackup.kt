package com.dewijones92.totum.backup

import com.dewijones92.totum.data.sponsorblock.SkipCategory
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.decodeSourceSkips
import com.dewijones92.totum.domain.encodeSourceSkips
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
                KEY_GAPLESS to gaplessQueue.toString(),
                KEY_EXPLORE to exploreByDefault.toString(),
                KEY_EXPLORE_CAP to exploreCap.toString(),
                KEY_REWIND to rewindOnResume.toString(),
                KEY_SILENT_TAIL to silentTailEnds.toString(),
                KEY_INBOX_ALL to inboxAll.toString(),
                KEY_INBOX_SOURCES to inboxSources.sorted().joinToString("\n"),
                KEY_SOURCE_SKIPS to encodeSourceSkips(sourceSkips),
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
            values[KEY_GAPLESS]?.toBooleanStrictOrNull()?.let(::setGaplessQueue)
            values[KEY_EXPLORE]?.toBooleanStrictOrNull()?.let(::setExploreByDefault)
            values[KEY_EXPLORE_CAP]?.toIntOrNull()?.let(::setExploreCap)
            values[KEY_REWIND]?.toBooleanStrictOrNull()?.let(::setRewindOnResume)
            values[KEY_SILENT_TAIL]?.toBooleanStrictOrNull()?.let(::setSilentTailEnds)
            values[KEY_INBOX_ALL]?.toBooleanStrictOrNull()?.let(::setInboxAll)
            values[KEY_INBOX_SOURCES]?.lines()?.filter { it.isNotBlank() }?.forEach {
                setInboxSource(
                    SourceId(it),
                    true
                )
            }
            values[KEY_SOURCE_SKIPS]?.let(
                ::decodeSourceSkips
            )?.forEach { (source, skips) -> setSourceSkips(source, skips) }
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
private const val KEY_GAPLESS = "gaplessQueue"
private const val KEY_EXPLORE = "exploreByDefault"
private const val KEY_EXPLORE_CAP = "exploreCap"
private const val KEY_REWIND = "rewindOnResume"
private const val KEY_SILENT_TAIL = "silentTailEnds"
private const val KEY_INBOX_ALL = "inboxAll"
private const val KEY_INBOX_SOURCES = "inboxSources"
private const val KEY_SOURCE_SKIPS = "sourceSkips"
