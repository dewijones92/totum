package com.dewijones92.totum.exsurge

import kotlinx.serialization.Serializable

@Serializable
public data class SettingsChange(val atEpochMs: Long, val source: String, val diff: String)

@Serializable
public data class SettingsLog(
    val changes: List<SettingsChange> = emptyList(),
    val lastSeen: ExsurgeSettings? = null,
) {
    public fun after(change: SettingsChange, settings: ExsurgeSettings): SettingsLog =
        SettingsLog((changes + change).takeLast(LIMIT), settings)

    public fun noticing(loaded: ExsurgeSettings, atEpochMs: Long): SettingsLog {
        val seen = lastSeen ?: return copy(lastSeen = loaded)
        if (seen == loaded) return this
        val change = SettingsChange(atEpochMs, STARTUP_SOURCE, ExsurgeCodec.settingsDiff(seen, loaded))
        return after(change, loaded)
    }

    public fun describe(time: (Long) -> String): String =
        if (changes.isEmpty()) {
            "none recorded"
        } else {
            "${changes.size} change(s), oldest first: " +
                changes.joinToString(" | ") { "${time(it.atEpochMs)} ${it.source}: ${it.diff}" }
        }

    public companion object {
        public const val LIMIT: Int = 20
        public const val STARTUP_SOURCE: String = "start-up: found changed since the last write (not by updateSettings)"
    }
}
