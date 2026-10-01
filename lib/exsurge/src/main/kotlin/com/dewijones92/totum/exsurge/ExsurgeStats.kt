package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

public enum class Rank(public val laurels: Int, public val latin: String) {
    TIRO(0, "Tiro"),
    LEGIONARIUS(10, "Legionarius"),
    CENTURIO(50, "Centurio"),
    TRIBUNUS(150, "Tribunus"),
    LEGATUS(300, "Legatus"),
    CONSUL(600, "Consul"),
    IMPERATOR(1000, "Imperator"),
    ;

    public val next: Rank? get() = entries.getOrNull(ordinal + 1)

    public companion object {
        public fun forLaurels(laurels: Int): Rank = entries.last { laurels >= it.laurels }
    }
}

public data class DayTally(
    val completed: Int = 0,
    val skipped: Int = 0,
    val missed: Int = 0,
    val steps: Int = 0,
    val minutesStanding: Long = 0,
) {
    val summons: Int get() = completed + skipped + missed
}

public data class ExsurgeStats(
    val today: DayTally,
    val lastSevenDays: DayTally,
    val streakDays: Int,
    val laurels: Int,
    val rank: Rank,
) {
    val laurelsToNextRank: Int? get() = rank.next?.let { it.laurels - laurels }

    public companion object {
        public fun of(outcomes: List<BreakOutcome>, now: Instant, zone: ZoneId): ExsurgeStats {
            val byDay = outcomes.groupBy { it.resolvedAt.atZone(zone).toLocalDate() }
            val today = now.atZone(zone).toLocalDate()
            val week = (0L..6L).map { today.minusDays(it) }
            val laurels = outcomes.count { it.kind == OutcomeKind.COMPLETED }
            return ExsurgeStats(
                today = tally(byDay[today].orEmpty()),
                lastSevenDays = tally(week.flatMap { byDay[it].orEmpty() }),
                streakDays = streak(byDay, today),
                laurels = laurels,
                rank = Rank.forLaurels(laurels),
            )
        }

        public fun promoted(before: List<BreakOutcome>, after: List<BreakOutcome>): Rank? {
            val was = Rank.forLaurels(before.count { it.kind == OutcomeKind.COMPLETED })
            val now = Rank.forLaurels(after.count { it.kind == OutcomeKind.COMPLETED })
            return now.takeIf { it > was }
        }

        private fun tally(outcomes: List<BreakOutcome>) = DayTally(
            completed = outcomes.count { it.kind == OutcomeKind.COMPLETED },
            skipped = outcomes.count { it.kind == OutcomeKind.SKIPPED },
            missed = outcomes.count { it.kind == OutcomeKind.MISSED },
            steps = outcomes.sumOf { it.steps },
            minutesStanding = outcomes.sumOf { o -> o.breakStartedAt?.let { Duration.between(it, o.resolvedAt).toMinutes() } ?: 0 },
        )

        private fun streak(byDay: Map<LocalDate, List<BreakOutcome>>, today: LocalDate): Int {
            val earliest = byDay.keys.minOrNull() ?: return 0
            var streak = 0
            var day = today
            while (!day.isBefore(earliest)) {
                val outcomes = byDay[day].orEmpty()
                when {
                    outcomes.isEmpty() -> Unit
                    outcomes.all { it.kind == OutcomeKind.COMPLETED } -> streak++
                    day == today -> return 0
                    else -> return streak
                }
                day = day.minusDays(1)
            }
            return streak
        }
    }
}
