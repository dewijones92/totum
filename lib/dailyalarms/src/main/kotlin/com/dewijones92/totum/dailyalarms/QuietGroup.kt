@file:UseSerializers(InstantSerializer::class)

package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.InstantSerializer
import com.dewijones92.totum.reminders.WeeklyWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

@Serializable
public data class QuietGroup(
    val id: String,
    val name: String,
    val emoji: String,
    val packages: List<String> = emptyList(),
    val window: WeeklyWindow = WeeklyWindow(),
    val pausedUntil: Instant? = null,
) {
    public fun isPausedAt(now: Instant): Boolean = pausedUntil?.isAfter(now) == true

    public fun state(now: Instant, zone: ZoneId): GroupState {
        val pause = pausedUntil?.takeIf { it.isAfter(now) }
        return when {
            pause != null -> GroupState(this, QuietKind.PAUSED, pause)
            window.isInside(now, zone) -> GroupState(this, QuietKind.ON, window.endAfter(now, zone))
            else -> GroupState(this, QuietKind.QUIET, window.nextStart(now, zone))
        }
    }

    public fun validated(): QuietGroup = copy(
        name = name.trim().ifEmpty { FALLBACK_NAME },
        emoji = emoji.trim().ifEmpty { EMOJI_CHOICES.first() },
        packages = packages.map(String::trim).filter(String::isNotEmpty).distinct(),
        window = WeeklyWindow(
            window.hours
                .mapValues { (_, hours) ->
                    DayHours(
                        hours.startMinuteOfDay.coerceIn(0, DayHours.MINUTES_PER_DAY - 1),
                        hours.endMinuteOfDay.coerceIn(1, DayHours.MINUTES_PER_DAY),
                    )
                }
                .filterValues { it.isUsable },
        ),
    )

    public companion object {
        public const val WORK_ID: String = "work"
        public const val SLACK: String = "com.Slack"
        public const val OUTLOOK: String = "com.microsoft.office.outlook"
        public const val TEAMS: String = "com.microsoft.teams"
        public val DEFAULT_PACKAGES: List<String> = listOf(SLACK, OUTLOOK, TEAMS)
        public val DEFAULT_DAY: DayHours =
            DayHours(9 * DayHours.MINUTES_PER_HOUR, 17 * DayHours.MINUTES_PER_HOUR + 30)
        public val DEFAULT_WINDOW: WeeklyWindow = WeeklyWindow.uniform(
            DayOfWeek.entries.filter { it <= DayOfWeek.FRIDAY }.toSet(),
            DEFAULT_DAY.startMinuteOfDay,
            DEFAULT_DAY.endMinuteOfDay,
        )
        public val NEW_GROUP_DAY: DayHours = DayHours(8 * DayHours.MINUTES_PER_HOUR, 22 * DayHours.MINUTES_PER_HOUR)
        public val EMOJI_CHOICES: List<String> = listOf("💼", "📱", "💬", "📧", "🎮", "🛒", "🏠", "🌙")
        private const val WORK_EMOJI = "💼"
        private const val WORK_NAME = "Work apps"
        private const val FALLBACK_NAME = "Group"

        public fun work(): QuietGroup = QuietGroup(WORK_ID, WORK_NAME, WORK_EMOJI, DEFAULT_PACKAGES, DEFAULT_WINDOW)

        public fun blank(id: String, name: String, emoji: String): QuietGroup =
            QuietGroup(
                id,
                name,
                emoji,
                emptyList(),
                WeeklyWindow.uniform(
                    DayOfWeek.entries.toSet(),
                    NEW_GROUP_DAY.startMinuteOfDay,
                    NEW_GROUP_DAY.endMinuteOfDay
                ),
            )
    }
}

@Serializable
public enum class QuietKind { QUIET, ON, PAUSED }

public data class GroupState(val group: QuietGroup, val kind: QuietKind, val until: Instant?) {
    public fun changedFrom(before: QuietKind?): Boolean =
        before != null && before != kind && kind != QuietKind.PAUSED && !(kind == QuietKind.QUIET && until == null)
}
