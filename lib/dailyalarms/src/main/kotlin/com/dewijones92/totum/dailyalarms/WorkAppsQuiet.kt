@file:UseSerializers(InstantSerializer::class)

package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.reminders.DayHours
import com.dewijones92.totum.reminders.InstantSerializer
import com.dewijones92.totum.reminders.WeeklyWindow
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

@Serializable
public data class WorkAppsSettings(
    val enabled: Boolean = false,
    val groups: List<QuietGroup> = listOf(QuietGroup.work()),
) {
    public fun validated(): WorkAppsSettings {
        val claimed = mutableSetOf<String>()
        return copy(
            groups = groups.distinctBy { it.id }.map { group ->
                val clean = group.validated()
                clean.copy(packages = clean.packages.filter(claimed::add))
            },
        )
    }

    public fun group(id: String): QuietGroup? = groups.firstOrNull { it.id == id }

    public fun groupOf(packageName: String): QuietGroup? = groups.firstOrNull { packageName in it.packages }

    public val packages: List<String> get() = groups.flatMap { it.packages }

    public fun withGroup(group: QuietGroup, at: Int? = null): WorkAppsSettings {
        val existing = groups.indexOfFirst { it.id == group.id }
        val next = when {
            existing >= 0 -> groups.toMutableList().also { it[existing] = group }
            at != null -> groups.toMutableList().also { it.add(at.coerceIn(0, it.size), group) }
            else -> groups + group
        }
        return copy(groups = next).validated()
    }

    public fun withoutGroup(id: String): WorkAppsSettings = copy(groups = groups.filterNot { it.id == id })

    public fun withApp(groupId: String, packageName: String, at: Int? = null): WorkAppsSettings {
        val trimmed = packageName.trim()
        return copy(
            groups = groups.map { group ->
                if (group.id == groupId) {
                    val packages = group.packages.toMutableList()
                    packages.add((at ?: packages.size).coerceIn(0, packages.size), trimmed)
                    group.copy(packages = packages)
                } else {
                    group.copy(packages = group.packages - trimmed)
                }
            },
        ).validated()
    }

    public fun withoutApp(groupId: String, packageName: String): WorkAppsSettings =
        edited(groupId) { it.copy(packages = it.packages - packageName) }

    public fun withDay(groupId: String, day: DayOfWeek, hours: DayHours?): WorkAppsSettings = edited(groupId) {
        it.copy(window = WeeklyWindow(if (hours == null) it.window.hours - day else it.window.hours + (day to hours)))
    }

    public fun withPause(groupId: String, until: Instant?): WorkAppsSettings =
        edited(groupId) { it.copy(pausedUntil = until) }

    public fun edited(groupId: String, change: (QuietGroup) -> QuietGroup): WorkAppsSettings =
        copy(groups = groups.map { if (it.id == groupId) change(it) else it }).validated()

    public fun decide(posted: PostedNotification, now: Instant, zone: ZoneId): QuietDecision {
        val group = groupOf(posted.packageName)
        return when {
            !enabled -> QuietDecision.LetThrough("quiet hours are switched off")
            group == null -> QuietDecision.LetThrough("not one of the chosen apps")
            posted.ongoing -> QuietDecision.LetThrough("it is an ongoing notification", group.id)
            posted.category in NEVER_SNOOZED_CATEGORIES ->
                QuietDecision.LetThrough("it is a ${posted.category}", group.id)
            group.isPausedAt(now) ->
                QuietDecision.LetThrough("${group.name} is paused until ${group.pausedUntil}", group.id)
            group.window.isInside(now, zone) -> QuietDecision.LetThrough("inside ${group.name} hours", group.id)
            else -> QuietDecision.Snooze(returnAt(group, now, zone), group.id)
        }
    }

    public fun status(accessGranted: Boolean, now: Instant, zone: ZoneId): WorkAppsStatus? = when {
        !enabled || groups.isEmpty() -> null
        !accessGranted -> WorkAppsStatus.NeedsAccess
        else -> WorkAppsStatus.Groups(groups.map { it.state(now, zone) })
    }

    private fun returnAt(group: QuietGroup, now: Instant, zone: ZoneId): Instant =
        (group.window.nextStart(now, zone) ?: now.plus(NO_WINDOW_RECHECK)).plus(RETURN_MARGIN)

    public companion object {
        public val PAUSE_CHOICES_HOURS: List<Int> = listOf(1, 3)
        private val RETURN_MARGIN: Duration = Duration.ofSeconds(5)
        private val NO_WINDOW_RECHECK: Duration = Duration.ofDays(1)
        private val NEVER_SNOOZED_CATEGORIES = setOf("call", "alarm")

        public fun labelFor(packageName: String): String = when (packageName) {
            QuietGroup.SLACK -> "Slack"
            QuietGroup.OUTLOOK -> "Outlook"
            QuietGroup.TEAMS -> "Teams"
            else -> packageName.substringAfterLast('.').replaceFirstChar(Char::uppercase)
        }
    }
}

public data class PostedNotification(val packageName: String, val ongoing: Boolean, val category: String?)

public sealed interface QuietDecision {
    public data class LetThrough(val why: String, val groupId: String? = null) : QuietDecision

    public data class Snooze(val until: Instant, val groupId: String) : QuietDecision
}

public sealed interface WorkAppsStatus {
    public val refreshAt: Instant?

    public data object NeedsAccess : WorkAppsStatus {
        override val refreshAt: Instant? = null
    }

    public data class Groups(val states: List<GroupState>) : WorkAppsStatus {
        override val refreshAt: Instant? = states.mapNotNull { it.until }.minOrNull()

        public val anyQuiet: Boolean get() = states.any { it.kind == QuietKind.QUIET }
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

@Serializable
private data class LegacyWorkAppsSettings(
    val enabled: Boolean = false,
    val packages: List<String> = QuietGroup.DEFAULT_PACKAGES,
    val window: WeeklyWindow = QuietGroup.DEFAULT_WINDOW,
    val pausedUntil: Instant? = null,
) {
    fun migrated(): WorkAppsSettings = WorkAppsSettings(
        enabled,
        listOf(QuietGroup.work().copy(packages = packages, window = window, pausedUntil = pausedUntil)),
    )
}

public object WorkAppsCodec {
    private const val GROUPS_KEY = "groups"
    private val KINDS = MapSerializer(String.serializer(), QuietKind.serializer())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    public fun encode(settings: WorkAppsSettings): String = json.encodeToString(WorkAppsSettings.serializer(), settings)

    public fun encodeKinds(kinds: Map<String, QuietKind>): String = json.encodeToString(KINDS, kinds)

    public fun decodeKinds(text: String): Map<String, QuietKind> = json.decodeFromString(KINDS, text)

    public fun isLegacy(text: String): Boolean = GROUPS_KEY !in json.parseToJsonElement(text).jsonObject

    public fun decode(text: String): WorkAppsSettings {
        val root = json.parseToJsonElement(text).jsonObject
        val settings = if (GROUPS_KEY in root) {
            json.decodeFromJsonElement(WorkAppsSettings.serializer(), root)
        } else {
            json.decodeFromJsonElement(LegacyWorkAppsSettings.serializer(), root).migrated()
        }
        return settings.validated()
    }
}
