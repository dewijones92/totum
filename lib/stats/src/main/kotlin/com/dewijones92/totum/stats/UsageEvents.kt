package com.dewijones92.totum.stats

public enum class RawKind { SCREEN_ON, SCREEN_OFF, KEYGUARD_HIDDEN, APP_FOREGROUND, APP_BACKGROUND }

public data class RawUsage(val at: Long, val kind: RawKind, val packageName: String? = null)

public data class Settled(val events: List<StatEvent>, val settledUntil: Long)

public fun settle(raw: List<RawUsage>, from: Long, now: Long): Settled {
    val until = now - FIRST_APP_WINDOW_MS
    if (until <= from) return Settled(emptyList(), from)
    val sorted = raw.sortedBy { it.at }
    val events = sorted.filter { it.at in from until until }.mapNotNull { usage ->
        when (usage.kind) {
            RawKind.SCREEN_ON -> StatEvent.PhoneScreen(usage.at, on = true)
            RawKind.SCREEN_OFF -> StatEvent.PhoneScreen(usage.at, on = false)
            RawKind.KEYGUARD_HIDDEN -> StatEvent.PhoneUnlock(usage.at, firstAppAt(sorted, usage.at))
            RawKind.APP_FOREGROUND, RawKind.APP_BACKGROUND -> null
        }
    }
    return Settled(events.distinct(), until)
}

private fun firstAppAt(sorted: List<RawUsage>, unlockAt: Long): String? {
    val apps = sorted.filter {
        it.kind == RawKind.APP_FOREGROUND && it.packageName != null && it.packageName !in SYSTEM_PACKAGES
    }
    return (
        apps.lastOrNull { it.at in unlockAt - FIRST_APP_LEAD_MS..unlockAt }
            ?: apps.firstOrNull { it.at in unlockAt..unlockAt + FIRST_APP_WINDOW_MS }
        )?.packageName
}

public fun unlockTimes(raw: List<RawUsage>): List<Long> =
    raw.filter { it.kind == RawKind.KEYGUARD_HIDDEN }.map { it.at }.distinct().sorted()

public fun foregroundApp(raw: List<RawUsage>): String? {
    val last = raw.filter { it.kind == RawKind.APP_FOREGROUND || it.kind == RawKind.APP_BACKGROUND }
        .maxWithOrNull(compareBy<RawUsage> { it.at }.thenBy { if (it.kind == RawKind.APP_FOREGROUND) 1 else 0 })
    return last?.takeIf { it.kind == RawKind.APP_FOREGROUND }?.packageName
}

internal const val FIRST_APP_WINDOW_MS = 5_000L
private const val FIRST_APP_LEAD_MS = 2_000L
private val SYSTEM_PACKAGES = setOf("com.android.systemui")
