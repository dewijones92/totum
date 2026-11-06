package com.dewijones92.totum.workapps

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.GroupState
import com.dewijones92.totum.dailyalarms.PostedNotification
import com.dewijones92.totum.dailyalarms.QuietDecision
import com.dewijones92.totum.dailyalarms.QuietGroup
import com.dewijones92.totum.dailyalarms.QuietKind
import com.dewijones92.totum.dailyalarms.Tally
import com.dewijones92.totum.dailyalarms.WorkAppsCodec
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.dailyalarms.WorkAppsStatus
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

interface WorkAppsStore {
    fun load(): WorkAppsSettings
    fun save(settings: WorkAppsSettings)
    fun loadKinds(): Map<String, QuietKind>
    fun saveKinds(kinds: Map<String, QuietKind>)
}

class InMemoryWorkAppsStore(
    private var settings: WorkAppsSettings = WorkAppsSettings(),
    private var kinds: Map<String, QuietKind> = emptyMap(),
) : WorkAppsStore {
    override fun load() = settings
    override fun save(settings: WorkAppsSettings) {
        this.settings = settings
    }

    override fun loadKinds() = kinds
    override fun saveKinds(kinds: Map<String, QuietKind>) {
        this.kinds = kinds
    }
}

class SharedPrefsWorkAppsStore(context: Context) : WorkAppsStore {
    private val prefs = JsonPrefs(context, "work_apps_quiet", WorkAppsQuiet.TAG)

    override fun load(): WorkAppsSettings = prefs.read(KEY, WorkAppsSettings()) { text ->
        val settings = WorkAppsCodec.decode(text)
        if (WorkAppsCodec.isLegacy(text)) {
            Diag.log(
                WorkAppsQuiet.TAG,
                "dewidebug workapps migrating the single app list into a default group: " +
                    "${settings.groups.map { it.name to it.packages }}",
            )
            save(settings)
        }
        settings
    }

    override fun save(settings: WorkAppsSettings) = prefs.write(KEY, WorkAppsCodec.encode(settings))

    override fun loadKinds(): Map<String, QuietKind> = prefs.read(KINDS_KEY, emptyMap(), WorkAppsCodec::decodeKinds)

    override fun saveKinds(kinds: Map<String, QuietKind>) = prefs.write(KINDS_KEY, WorkAppsCodec.encodeKinds(kinds))

    private companion object {
        const val KEY = "settings"
        const val KINDS_KEY = "kinds"
    }
}

data class QuietEnded(val packages: Set<String>, val source: String)

data class RemovedGroup(val group: QuietGroup, val index: Int)

data class RemovedApp(val groupId: String, val packageName: String, val index: Int)

class WorkAppsQuiet(
    private val store: WorkAppsStore,
    private val accessGranted: () -> Boolean,
    private val onChanged: (String) -> Unit,
    private val announce: (GroupState) -> String? = { null },
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<WorkAppsSettings> = _settings.asStateFlow()

    private val _quietEnded = MutableSharedFlow<QuietEnded>(extraBufferCapacity = QUIET_ENDED_BUFFER)
    val quietEnded: SharedFlow<QuietEnded> = _quietEnded.asSharedFlow()

    private val snoozed = Tally()
    private val letThrough = Tally()
    private val noticesNotShown = Tally()

    @Volatile
    private var lastDecision = "none yet"

    @Volatile
    private var lastNoticeNotShown = "none"

    private val kindsLock = Any()
    private var knownKinds: Map<String, QuietKind> = store.loadKinds()
    private var quietPackages: Set<String> = quietNow()

    init {
        Diag.log(TAG, "dewidebug workapps loaded: ${_settings.value}")
    }

    fun isQuietNow(packageName: String): Boolean = packageName in quietNow()

    private fun quietNow(): Set<String> {
        val settings = _settings.value
        val now = clock()
        val zone = zone()
        return settings.packages.filterTo(mutableSetOf()) { packageName ->
            settings.decide(PostedNotification(packageName, ongoing = false, category = null), now, zone) is
                QuietDecision.Snooze
        }
    }

    private fun releaseWhatStoppedBeingQuiet(source: String) {
        val quiet = quietNow()
        val ended = quietPackages - quiet
        quietPackages = quiet
        if (ended.isEmpty()) return
        Diag.log(
            TAG,
            "dewidebug workapps ${ended.sorted()} stopped being quiet ($source at ${clock()}): " +
                "asking the listener to hand back what it snoozed",
        )
        _quietEnded.tryEmit(QuietEnded(ended, source))
    }

    fun hasAccess(): Boolean = accessGranted()

    fun status(): WorkAppsStatus? = _settings.value.status(accessGranted(), clock(), zone())

    fun pauseFor(groupId: String, hours: Int, source: String) =
        pauseUntil(groupId, clock().plus(Duration.ofHours(hours.toLong())), source)

    fun pauseUntilNextWorkStart(groupId: String, source: String) {
        val group = _settings.value.group(groupId) ?: return
        val next = group.window.nextStart(clock(), zone())
        if (next == null) {
            Diag.warn(TAG, "dewidebug workapps pause until the next work day refused for ${group.name}: no day is set")
            return
        }
        pauseUntil(groupId, next, source)
    }

    fun resume(groupId: String, source: String) = update(source) { it.withPause(groupId, null) }

    private fun pauseUntil(groupId: String, until: Instant, source: String) =
        update(source) { it.withPause(groupId, until) }

    @Synchronized
    fun update(source: String, change: (WorkAppsSettings) -> WorkAppsSettings) {
        val before = _settings.value
        val after = change(before).validated()
        if (after == before) return
        _settings.value = after
        store.save(after)
        Diag.log(TAG, "dewidebug workapps settings from=$source: $before -> $after")
        val edited = editedGroups(before, after)
        noteBoundaries("settings: $source", silentFor = edited)
        onChanged("work apps settings: $source")
    }

    private fun editedGroups(before: WorkAppsSettings, after: WorkAppsSettings): Set<String> =
        if (before.enabled != after.enabled) {
            (before.groups + after.groups).mapTo(mutableSetOf()) { it.id }
        } else {
            (before.groups.map { it.id } + after.groups.map { it.id }).filterTo(mutableSetOf()) { id ->
                before.group(id) != after.group(id)
            }
        }

    fun noteBoundaries(source: String, silentFor: Set<String> = emptySet()) = synchronized(kindsLock) {
        val now = clock()
        val states = (status() as? WorkAppsStatus.Groups)?.states.orEmpty()
        val before = knownKinds
        states.forEach { state -> noteGroup(state, before[state.group.id], source, now, state.group.id in silentFor) }
        rememberKinds(source, states)
        releaseWhatStoppedBeingQuiet(source)
    }

    private fun noteGroup(state: GroupState, was: QuietKind?, source: String, now: Instant, edited: Boolean) {
        val name = state.group.name
        when {
            state.changedFrom(was) && !edited -> {
                Diag.log(
                    TAG,
                    "dewidebug workapps $name went $was -> ${state.kind} until ${state.until} " +
                        "($source at $now): telling the person",
                )
                announce(state)?.let { reason -> noticeNotShown(state, reason) }
            }
            was != state.kind -> Diag.log(
                TAG,
                "dewidebug workapps $name is now ${state.kind} (was $was) ($source at $now): not telling the person, " +
                    when {
                        edited -> "that group was changed by the edit that just ran"
                        was == null -> "nothing recorded to compare with"
                        else -> "that change is silent by rule"
                    },
            )
        }
    }

    private fun noticeNotShown(state: GroupState, reason: String) {
        val count = noticesNotShown.record("${state.group.name} ${state.kind}: $reason")
        lastNoticeNotShown = "${state.group.name} ${state.kind}: $reason at ${clock()}"
        if (Tally.shouldLog(count)) {
            Diag.log(
                TAG,
                "dewidebug workapps notice not shown: $reason (${state.group.name} went ${state.kind}; " +
                    "#$count of this kind; the change is not retried; all: ${noticesNotShown.summary()})",
            )
        }
    }

    private fun rememberKinds(source: String, states: List<GroupState>? = null) = synchronized(kindsLock) {
        val current = states ?: (status() as? WorkAppsStatus.Groups)?.states.orEmpty()
        val kinds = current.associate { it.group.id to it.kind }
        if (kinds != knownKinds) {
            Diag.log(TAG, "dewidebug workapps remembered group states $knownKinds -> $kinds ($source)")
            knownKinds = kinds
            store.saveKinds(kinds)
        }
    }

    fun accessChanged(source: String) {
        Diag.log(TAG, "dewidebug workapps notification access granted=${accessGranted()} ($source)")
        onChanged("work apps access: $source")
    }

    fun decide(posted: PostedNotification): QuietDecision {
        val settings = _settings.value
        val now = clock()
        val decision = settings.decide(posted, now, zone())
        val group = settings.groupOf(posted.packageName) ?: return decision
        when (decision) {
            is QuietDecision.Snooze -> {
                val count = snoozed.record("${group.name}: ${posted.packageName} until ${decision.until}")
                lastDecision = "snooze ${posted.packageName} (${group.name}) until ${decision.until} at $now"
                if (Tally.shouldLog(count)) {
                    Diag.log(
                        TAG,
                        "dewidebug workapps snoozing ${posted.packageName} of ${group.name} until ${decision.until} " +
                            "(that is #$count for this return time; all: ${snoozed.summary()})",
                    )
                }
            }
            is QuietDecision.LetThrough -> {
                val count = letThrough.record("${group.name}: ${posted.packageName}: ${decision.why}")
                lastDecision = "let through ${posted.packageName} (${group.name}: ${decision.why}) at $now"
                if (Tally.shouldLog(count)) {
                    Diag.log(
                        TAG,
                        "dewidebug workapps letting ${posted.packageName} of ${group.name} through: ${decision.why} " +
                            "(#$count of this kind; all: ${letThrough.summary()})",
                    )
                }
            }
        }
        return decision
    }

    fun snoozeFailed(packageName: String, error: Throwable) =
        Diag.warn(TAG, "dewidebug workapps could not snooze a $packageName notification; it stays visible", error)

    val diagnostics: Map<String, String>
        get() = buildMap {
            val settings = _settings.value
            put("workApps.enabled", settings.enabled.toString())
            put("workApps.accessGranted", accessGranted().toString())
            put(
                "workApps.groups",
                settings.groups.joinToString(" || ") { group ->
                    val hours = group.window.hours.entries.sortedBy { it.key }
                        .joinToString(" | ") { (day, h) -> "$day ${h.startMinuteOfDay}-${h.endMinuteOfDay}" }
                        .ifEmpty { "no days" }
                    "${group.emoji} ${group.name} apps=${group.packages.joinToString("|").ifEmpty { "none" }} " +
                        "window=$hours paused=${group.pausedUntil ?: "-"}"
                }.ifEmpty { "no groups" },
            )
            put("workApps.knownKinds", knownKinds.toString())
            put("workApps.status", status()?.toString() ?: "off")
            put("workApps.snoozed", snoozed.summary())
            put("workApps.letThrough", letThrough.summary())
            put("workApps.noticesNotShown", noticesNotShown.summary())
            put("workApps.lastNoticeNotShown", lastNoticeNotShown)
            put("workApps.lastDecision", lastDecision)
        }

    companion object {
        const val TAG = "workapps"
        private const val QUIET_ENDED_BUFFER = 8

        fun accessGranted(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
