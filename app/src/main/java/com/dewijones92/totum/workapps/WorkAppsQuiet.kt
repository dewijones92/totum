package com.dewijones92.totum.workapps

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.PostedNotification
import com.dewijones92.totum.dailyalarms.QuietDecision
import com.dewijones92.totum.dailyalarms.Tally
import com.dewijones92.totum.dailyalarms.WorkAppsCodec
import com.dewijones92.totum.dailyalarms.WorkAppsSettings
import com.dewijones92.totum.dailyalarms.WorkAppsStatus
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

interface WorkAppsStore {
    fun load(): WorkAppsSettings
    fun save(settings: WorkAppsSettings)
}

class InMemoryWorkAppsStore(private var settings: WorkAppsSettings = WorkAppsSettings()) : WorkAppsStore {
    override fun load() = settings
    override fun save(settings: WorkAppsSettings) {
        this.settings = settings
    }
}

class SharedPrefsWorkAppsStore(context: Context) : WorkAppsStore {
    private val prefs = JsonPrefs(context, "work_apps_quiet", WorkAppsQuiet.TAG)

    override fun load(): WorkAppsSettings = prefs.read(KEY, WorkAppsSettings(), WorkAppsCodec::decode)

    override fun save(settings: WorkAppsSettings) = prefs.write(KEY, WorkAppsCodec.encode(settings))

    private companion object {
        const val KEY = "settings"
    }
}

class WorkAppsQuiet(
    private val store: WorkAppsStore,
    private val accessGranted: () -> Boolean,
    private val onChanged: (String) -> Unit,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<WorkAppsSettings> = _settings.asStateFlow()

    private val snoozed = Tally()
    private val letThrough = Tally()

    @Volatile
    private var lastDecision = "none yet"

    fun hasAccess(): Boolean = accessGranted()

    fun status(): WorkAppsStatus? = _settings.value.status(accessGranted(), clock(), zone())

    fun pauseFor(hours: Int, source: String) = pauseUntil(clock().plus(Duration.ofHours(hours.toLong())), source)

    fun pauseUntilNextWorkStart(source: String) {
        val next = _settings.value.window.nextStart(clock(), zone())
        if (next == null) {
            Diag.warn(TAG, "dewidebug workapps pause until the next work day refused: no work day is set")
            return
        }
        pauseUntil(next, source)
    }

    fun resume(source: String) = update(source) { it.copy(pausedUntil = null) }

    private fun pauseUntil(until: Instant, source: String) = update(source) { it.copy(pausedUntil = until) }

    @Synchronized
    fun update(source: String, change: (WorkAppsSettings) -> WorkAppsSettings) {
        val before = _settings.value
        val after = change(before).validated()
        if (after == before) return
        _settings.value = after
        store.save(after)
        Diag.log(TAG, "dewidebug workapps settings from=$source: $before -> $after")
        onChanged("work apps settings: $source")
    }

    fun accessChanged(source: String) {
        Diag.log(TAG, "dewidebug workapps notification access granted=${accessGranted()} ($source)")
        onChanged("work apps access: $source")
    }

    fun decide(posted: PostedNotification): QuietDecision {
        val settings = _settings.value
        val now = clock()
        val decision = settings.decide(posted, now, zone())
        if (posted.packageName !in settings.packages) return decision
        when (decision) {
            is QuietDecision.Snooze -> {
                val count = snoozed.record("${posted.packageName} until ${decision.until}")
                lastDecision = "snooze ${posted.packageName} until ${decision.until} at $now"
                if (Tally.shouldLog(count)) {
                    Diag.log(
                        TAG,
                        "dewidebug workapps snoozing ${posted.packageName} until ${decision.until} " +
                            "(that is #$count for this return time; all: ${snoozed.summary()})",
                    )
                }
            }
            is QuietDecision.LetThrough -> {
                val count = letThrough.record("${posted.packageName}: ${decision.why}")
                lastDecision = "let through ${posted.packageName} (${decision.why}) at $now"
                if (Tally.shouldLog(count)) {
                    Diag.log(
                        TAG,
                        "dewidebug workapps letting ${posted.packageName} through: ${decision.why} " +
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
            put("workApps.apps", settings.packages.joinToString(" | ").ifEmpty { "none" })
            put(
                "workApps.window",
                settings.window.hours.entries.sortedBy { it.key }
                    .joinToString(" | ") { (day, h) -> "$day ${h.startMinuteOfDay}-${h.endMinuteOfDay}" }
                    .ifEmpty { "no work days" }
            )
            put("workApps.pausedUntil", settings.pausedUntil?.toString() ?: "-")
            put("workApps.status", status()?.toString() ?: "off")
            put("workApps.snoozed", snoozed.summary())
            put("workApps.letThrough", letThrough.summary())
            put("workApps.lastDecision", lastDecision)
        }

    companion object {
        const val TAG = "workapps"

        fun accessGranted(context: Context): Boolean =
            context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}
