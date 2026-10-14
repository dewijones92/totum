package com.dewijones92.totum.stats

public enum class BreakKind { COMPLETED, SKIPPED, MISSED }

public data class BreakRecord(
    val at: Long,
    val kind: BreakKind,
    val credited: Boolean,
    val snoozes: Int,
    val steps: Int,
    val standingMs: Long,
    val practised: Boolean,
)

public data class ExsurgeTotals(
    val summons: Int,
    val obeyed: Int,
    val skipped: Int,
    val missed: Int,
    val snoozes: Int,
    val walkPresses: Int,
    val pauses: Int,
    val steps: Int,
    val standingMs: Long,
    val practised: Int,
    val offMs: Long,
)

public fun exsurgeTotals(
    breaks: List<BreakRecord>,
    events: List<StatEvent>,
    since: Long?,
    now: Long,
    onNow: Boolean,
): ExsurgeTotals {
    val inRange = breaks.filter { since == null || it.at >= since }
    val actions = events.filterIsInstance<StatEvent.ExsurgeAction>().filter { since == null || it.at >= since }
    return ExsurgeTotals(
        summons = inRange.size,
        obeyed = inRange.count { it.credited },
        skipped = inRange.count { it.kind == BreakKind.SKIPPED },
        missed = inRange.count { it.kind == BreakKind.MISSED },
        snoozes = inRange.sumOf { it.snoozes },
        walkPresses = actions.count { it.action == ExsurgeMove.JUST_WALK },
        pauses = actions.count { it.action == ExsurgeMove.PAUSE_HOUR },
        steps = inRange.sumOf { it.steps },
        standingMs = inRange.sumOf { it.standingMs },
        practised = inRange.count { it.credited && it.practised },
        offMs = offMs(events.filterIsInstance<StatEvent.ExsurgeSwitch>(), since, now, onNow),
    )
}

public fun offMs(switches: List<StatEvent.ExsurgeSwitch>, since: Long?, now: Long, onNow: Boolean): Long {
    val start = since ?: switches.minOfOrNull { it.at } ?: now
    var total = 0L
    var offFrom: Long? = null
    switches.sortedBy { it.at }.forEach { switch ->
        if (!switch.on && offFrom == null) offFrom = switch.at
        if (switch.on) {
            offFrom?.let { total += clipped(it, switch.at, start) }
            offFrom = null
        }
    }
    val stillOff = offFrom ?: if (!onNow && switches.isEmpty()) start else null
    if (!onNow) stillOff?.let { total += clipped(it, now, start) }
    return total
}

private fun clipped(from: Long, to: Long, start: Long): Long = (to - maxOf(from, start)).coerceAtLeast(0)

public data class NudgeTotals(val posted: Int, val turnedOn: Int, val notToday: Int, val ignored: Int)

public fun nudgeTotals(events: List<StatEvent>, since: Long?): NudgeTotals {
    val inRange = events.filter { since == null || it.at >= since }
    val posted = inRange.filterIsInstance<StatEvent.Nudge>().filter { it.what == NudgeWhat.POSTED }
    val ons = inRange.filterIsInstance<StatEvent.ExsurgeSwitch>().filter { it.on }.map { it.at }
    val nos = inRange.filterIsInstance<StatEvent.Nudge>().filter { it.what == NudgeWhat.NOT_TODAY }.map { it.at }
    fun followed(at: Long, by: List<Long>) = by.any { it in at..at + NUDGE_ANSWER_MS }
    val turnedOn = posted.count { followed(it.at, ons) }
    val notToday = posted.count { !followed(it.at, ons) && followed(it.at, nos) }
    return NudgeTotals(posted.size, turnedOn, notToday, posted.size - turnedOn - notToday)
}

public data class AlarmTotals(
    val asked: Int,
    val yes: Int,
    val no: Int,
    val rang: Int,
    val snoozed: Int,
    val dismissed: Int,
    val averageStopMs: Long?,
)

public fun alarmTotals(events: List<StatEvent>, since: Long?): AlarmTotals {
    val changes = events.filterIsInstance<StatEvent.AlarmChange>().filter { since == null || it.at >= since }
        .sortedBy { it.at }
    val ringingSince = mutableMapOf<String, Long>()
    val stops = mutableListOf<Long>()
    changes.forEach { change ->
        val startsRinging = change.before != AlarmPhase.RINGING && change.before != AlarmPhase.SNOOZED
        if (change.after == AlarmPhase.RINGING && startsRinging) {
            ringingSince[change.alarmId] = change.at
        }
        if (change.event == AlarmStep.DISMISS) ringingSince.remove(change.alarmId)?.let { stops += change.at - it }
    }
    return AlarmTotals(
        asked = changes.count { it.after == AlarmPhase.ASKING && it.before != AlarmPhase.ASKING },
        yes = changes.count { it.event == AlarmStep.ANSWER || it.event == AlarmStep.SET_TODAY },
        no = changes.count { it.event == AlarmStep.DECLINE },
        rang = changes.count { it.after == AlarmPhase.RINGING && it.before != AlarmPhase.RINGING },
        snoozed = changes.count { it.event == AlarmStep.SNOOZE },
        dismissed = changes.count { it.event == AlarmStep.DISMISS },
        averageStopMs = stops.takeIf { it.isNotEmpty() }?.average()?.toLong(),
    )
}

public data class UseTotals(
    val searches: Int,
    val downloads: Int,
    val downloadedBytes: Long,
    val pins: Int,
    val shuffles: Int,
    val videosShown: Int,
)

public fun useTotals(events: List<StatEvent>, since: Long?): UseTotals {
    val uses = events.filterIsInstance<StatEvent.AppUse>().filter { since == null || it.at >= since }
    fun count(kind: UseKind) = uses.count { it.what == kind }
    return UseTotals(
        searches = count(UseKind.SEARCH),
        downloads = count(UseKind.DOWNLOAD),
        downloadedBytes = uses.filter { it.what == UseKind.DOWNLOAD }.sumOf { it.bytes ?: 0 },
        pins = count(UseKind.PIN),
        shuffles = count(UseKind.SHUFFLE),
        videosShown = count(UseKind.VIDEOS_SHOWN),
    )
}

private const val NUDGE_ANSWER_MS = 15 * 60 * 1_000L
