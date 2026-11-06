package com.dewijones92.totum.bedtime

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.dailyalarms.Tally
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.reminders.BedtimeCodec
import com.dewijones92.totum.reminders.BedtimeContext
import com.dewijones92.totum.reminders.BedtimeDecision
import com.dewijones92.totum.reminders.BedtimeSettings
import com.dewijones92.totum.reminders.BedtimeStatus
import com.dewijones92.totum.reminders.kit.JsonPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration as KDuration

interface BedtimeStore {
    fun load(): BedtimeSettings
    fun save(settings: BedtimeSettings)
}

class InMemoryBedtimeStore(private var settings: BedtimeSettings = BedtimeSettings()) : BedtimeStore {
    override fun load() = settings
    override fun save(settings: BedtimeSettings) {
        this.settings = settings
    }
}

class SharedPrefsBedtimeStore(context: Context) : BedtimeStore {
    private val prefs = JsonPrefs(context, "bedtime_sleep_timer", Bedtime.TAG)

    override fun load(): BedtimeSettings = prefs.read(KEY, BedtimeSettings(), BedtimeCodec::decode)

    override fun save(settings: BedtimeSettings) = prefs.write(KEY, BedtimeCodec.encode(settings))

    private companion object {
        const val KEY = "settings"
    }
}

class Bedtime(
    private val store: BedtimeStore,
    private val sleepTimer: SleepTimer,
    private val playback: StateFlow<PlaybackState?>,
    private val kidLocked: () -> Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<BedtimeSettings> = _settings.asStateFlow()

    private var armedRunEnd: Instant? = null
    private var declinedRunEnd: Instant? = null
    private val skipped = Tally()

    @Volatile
    private var lastDecision = "none yet"

    val fade: KDuration get() = _settings.value.fadeSeconds.seconds

    val timerState: StateFlow<SleepTimerState> get() = sleepTimer.state

    init {
        Diag.log(TAG, "dewidebug bedtime loaded: ${_settings.value}")
    }

    fun status(): BedtimeStatus = _settings.value.status(clock(), zone(), context())

    fun update(source: String, change: (BedtimeSettings) -> BedtimeSettings) {
        val before = _settings.value
        val after = change(before).validated()
        if (after == before) return
        _settings.value = after
        store.save(after)
        Diag.log(TAG, "dewidebug bedtime settings from=$source: $before -> $after")
    }

    fun start() {
        scope.launch {
            playback.map { state -> state?.takeIf { it.isPlaying }?.itemId }.distinctUntilChanged().collect { id ->
                if (id != null) evaluate("playback started or moved on to $id")
            }
        }
        scope.launch {
            sleepTimer.cancellations.collect {
                val run = armedRunEnd ?: return@collect
                declinedRunEnd = run
                armedRunEnd = null
                Diag.log(
                    TAG,
                    "dewidebug bedtime: the person cancelled the sleep timer, so no more tonight (until $run)"
                )
            }
        }
        scope.launch { _settings.drop(1).collect { evaluate("settings changed") } }
        scope.launch { _settings.collectLatest { settings -> waitForBedtimes(settings) } }
    }

    private suspend fun waitForBedtimes(settings: BedtimeSettings) {
        while (scope.isActive) {
            val now = clock()
            val next = settings.nextStart(now, zone())
            val wait = next?.let { Duration.between(now, it).toMillis() } ?: MAX_WAIT_MS
            delay(wait.coerceIn(MIN_WAIT_MS, MAX_WAIT_MS))
            evaluate("bedtime check, next start was $next")
        }
    }

    private fun context(): BedtimeContext {
        val state = playback.value
        return BedtimeContext(
            timerArmed = sleepTimer.state.value != SleepTimerState.Off,
            kidLocked = kidLocked(),
            live = state?.live == true,
            declinedRunEnd = declinedRunEnd,
        )
    }

    fun evaluate(trigger: String) {
        val now = clock()
        val state = playback.value
        val context = context()
        when (val decision = _settings.value.decide(now, zone(), context)) {
            is BedtimeDecision.Skip -> noteSkip(trigger, decision.why, context, now)
            is BedtimeDecision.Arm -> {
                if (state?.isPlaying != true) {
                    noteSkip(trigger, "nothing is playing to put a timer on", context, now)
                    return
                }
                lastDecision = "armed at $now for ${state.itemId} until ${decision.runEnd} ($trigger)"
                Diag.log(
                    TAG,
                    "dewidebug bedtime arming the sleep timer to stop after ${state.itemId} " +
                        "(\"${state.title}\", ${state.positionMs}ms of ${state.durationMs}ms) and fade over $fade: " +
                        "$trigger; the night ends ${decision.runEnd}",
                )
                armedRunEnd = decision.runEnd
                sleepTimer.stopAfterCurrentItem()
            }
        }
    }

    private fun noteSkip(trigger: String, why: String, context: BedtimeContext, now: Instant) {
        lastDecision = "not armed at $now: $why ($trigger)"
        val count = skipped.record(why)
        if (Tally.shouldLog(count)) {
            Diag.log(
                TAG,
                "dewidebug bedtime not arming the sleep timer: $why (#$count for this reason; trigger: $trigger; " +
                    "armed=${context.timerArmed} kidLocked=${context.kidLocked} live=${context.live} " +
                    "declinedUntil=${context.declinedRunEnd}; all: ${skipped.summary()})",
            )
        }
    }

    val diagnostics: Map<String, String>
        get() = buildMap {
            val settings = _settings.value
            put("bedtime.enabled", settings.enabled.toString())
            put("bedtime.nights", settings.nights.sorted().joinToString("|").ifEmpty { "none" })
            put("bedtime.startMinute", settings.startMinuteOfDay.toString())
            put("bedtime.wakeMinute", settings.wakeMinuteOfDay.toString())
            put("bedtime.fadeSeconds", settings.fadeSeconds.toString())
            put("bedtime.status", status().toString())
            put("bedtime.armedRunEnd", armedRunEnd?.toString() ?: "-")
            put("bedtime.declinedRunEnd", declinedRunEnd?.toString() ?: "-")
            put("bedtime.skips", skipped.summary())
            put("bedtime.lastDecision", lastDecision)
        }

    companion object {
        const val TAG = "bedtime"
        private const val MIN_WAIT_MS = 1_000L
        private const val MAX_WAIT_MS = 6 * 60 * 60_000L
    }
}
