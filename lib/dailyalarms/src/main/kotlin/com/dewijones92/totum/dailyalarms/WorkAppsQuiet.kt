@file:UseSerializers(InstantSerializer::class)

package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.InstantSerializer
import com.dewijones92.totum.reminders.WeeklyWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@Serializable
public data class WorkAppsSettings(
    val enabled: Boolean = false,
    val packages: List<String> = DEFAULT_PACKAGES,
    val window: WeeklyWindow = DEFAULT_WINDOW,
    val pausedUntil: Instant? = null,
) {
    public fun validated(): WorkAppsSettings = copy(
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

    public fun withApp(packageName: String): WorkAppsSettings = copy(packages = packages + packageName).validated()

    public fun withoutApp(packageName: String): WorkAppsSettings = copy(packages = packages - packageName)

    public fun withDay(day: DayOfWeek, hours: DayHours?): WorkAppsSettings =
        copy(
            window = WeeklyWindow(if (hours == null) window.hours - day else window.hours + (day to hours))
        ).validated()

    public fun isPausedAt(now: Instant): Boolean = pausedUntil?.isAfter(now) == true

    public fun decide(posted: PostedNotification, now: Instant, zone: ZoneId): QuietDecision = when {
        !enabled -> QuietDecision.LetThrough("quiet hours are switched off")
        posted.packageName !in packages -> QuietDecision.LetThrough("not one of the chosen apps")
        posted.ongoing -> QuietDecision.LetThrough("it is an ongoing notification")
        posted.category in NEVER_SNOOZED_CATEGORIES -> QuietDecision.LetThrough("it is a ${posted.category}")
        isPausedAt(now) -> QuietDecision.LetThrough("paused until $pausedUntil")
        window.isInside(now, zone) -> QuietDecision.LetThrough("inside work hours")
        else -> QuietDecision.Snooze(returnAt(now, zone))
    }

    public fun status(accessGranted: Boolean, now: Instant, zone: ZoneId): WorkAppsStatus? = when {
        !enabled -> null
        !accessGranted -> WorkAppsStatus.NeedsAccess
        isPausedAt(now) -> WorkAppsStatus.Paused(pausedUntil!!)
        window.isInside(now, zone) -> WorkAppsStatus.On(packages, window.endAfter(now, zone))
        else -> WorkAppsStatus.Quiet(packages, window.nextStart(now, zone))
    }

    private fun returnAt(now: Instant, zone: ZoneId): Instant =
        (window.nextStart(now, zone) ?: now.plus(NO_WINDOW_RECHECK)).plus(RETURN_MARGIN)

    public companion object {
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
        public val PAUSE_CHOICES_HOURS: List<Int> = listOf(1, 3)
        private val RETURN_MARGIN: Duration = Duration.ofSeconds(5)
        private val NO_WINDOW_RECHECK: Duration = Duration.ofDays(1)
        private val NEVER_SNOOZED_CATEGORIES = setOf("call", "alarm")

        public fun labelFor(packageName: String): String = when (packageName) {
            SLACK -> "Slack"
            OUTLOOK -> "Outlook"
            TEAMS -> "Teams"
            else -> packageName.substringAfterLast('.').replaceFirstChar(Char::uppercase)
        }
    }
}

public data class PostedNotification(val packageName: String, val ongoing: Boolean, val category: String?)

public sealed interface QuietDecision {
    public data class LetThrough(val why: String) : QuietDecision

    public data class Snooze(val until: Instant) : QuietDecision
}

public sealed interface WorkAppsStatus {
    public val refreshAt: Instant?

    public data object NeedsAccess : WorkAppsStatus {
        override val refreshAt: Instant? = null
    }

    public data class Paused(val until: Instant) : WorkAppsStatus {
        override val refreshAt: Instant = until
    }

    public data class Quiet(val packages: List<String>, val until: Instant?) : WorkAppsStatus {
        override val refreshAt: Instant? = until
    }

    public data class On(val packages: List<String>, val until: Instant) : WorkAppsStatus {
        override val refreshAt: Instant = until
    }
}

public class Tally {
    private val counts = mutableMapOf<String, Int>()

    @Synchronized
    public fun record(key: String): Int = counts.merge(key, 1, Int::plus)!!

    @Synchronized
    public fun summary(): String =
        counts.entries.joinToString(" | ") { (key, n) -> "$key x$n" }.ifEmpty { "none yet" }

    public companion object {
        public fun shouldLog(count: Int): Boolean = count == 1 || count % LOG_EVERY == 0
        private const val LOG_EVERY = 10
    }
}

public object WorkAppsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(settings: WorkAppsSettings): String = json.encodeToString(WorkAppsSettings.serializer(), settings)

    public fun decode(text: String): WorkAppsSettings =
        json.decodeFromString(WorkAppsSettings.serializer(), text).validated()
}
