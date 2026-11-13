package com.dewijones92.totum.phoneuse

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.JsonPrefs
import com.dewijones92.totum.stats.NudgeBlock
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.UnlockNudgeCodec
import com.dewijones92.totum.stats.UnlockNudgeDecision
import com.dewijones92.totum.stats.UnlockNudgeSettings
import com.dewijones92.totum.stats.UnlockNudgeWhat
import com.dewijones92.totum.stats.foregroundApp
import com.dewijones92.totum.stats.settle
import com.dewijones92.totum.stats.unlockTimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

interface PhoneUseStore {
    fun settings(): UnlockNudgeSettings
    fun saveSettings(settings: UnlockNudgeSettings)
    fun cursor(): Long?
    fun saveCursor(epochMs: Long)
}

class InMemoryPhoneUseStore(
    private var settings: UnlockNudgeSettings = UnlockNudgeSettings(),
    private var cursor: Long? = null,
) : PhoneUseStore {
    override fun settings() = settings
    override fun saveSettings(settings: UnlockNudgeSettings) {
        this.settings = settings
    }

    override fun cursor() = cursor
    override fun saveCursor(epochMs: Long) {
        cursor = epochMs
    }
}

class SharedPrefsPhoneUseStore(context: Context) : PhoneUseStore {
    private val prefs = JsonPrefs(context, "phone_use", PhoneUse.TAG)

    override fun settings(): UnlockNudgeSettings = prefs.read(
        SETTINGS_KEY,
        UnlockNudgeSettings(),
        UnlockNudgeCodec::decode
    )

    override fun saveSettings(settings: UnlockNudgeSettings) = prefs.write(
        SETTINGS_KEY,
        UnlockNudgeCodec.encode(settings)
    )

    override fun cursor(): Long? = prefs.read(CURSOR_KEY, null) { text -> text.toLongOrNull() }

    override fun saveCursor(epochMs: Long) = prefs.write(CURSOR_KEY, epochMs.toString())

    private companion object {
        const val SETTINGS_KEY = "nudge"
        const val CURSOR_KEY = "syncedUntil"
    }
}

@Suppress("TooManyFunctions")
class PhoneUse(
    private val store: PhoneUseStore,
    private val ports: PhoneUsePorts,
    private val record: (StatEvent) -> Unit,
    private val context: PhoneUseContext,
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val settleDelayMs: Long = SETTLE_MS,
) {
    private val _settings = MutableStateFlow(store.settings())
    val settings: StateFlow<UnlockNudgeSettings> = _settings.asStateFlow()

    private val syncing = Mutex()

    @Volatile
    private var lastDecision = "no unlock seen yet"

    @Volatile
    private var lastSync = "not yet"

    @Volatile
    private var listening = false

    init {
        Diag.log(TAG, "dewidebug phoneuse loaded: ${_settings.value}; usage access=${ports.accessGranted()}")
    }

    fun accessGranted(): Boolean = ports.accessGranted()

    fun screenOn(): Boolean = ports.screenOn()

    fun update(source: String, change: (UnlockNudgeSettings) -> UnlockNudgeSettings) {
        val before = _settings.value
        val after = change(before).validated()
        if (after == before) return
        _settings.value = after
        store.saveSettings(after)
        Diag.log(TAG, "dewidebug phoneuse settings from=$source: $before -> $after")
    }

    fun start() {
        scope.launch {
            var listener: AutoCloseable? = null
            _settings.map { it.enabled }.distinctUntilChanged().collect { enabled ->
                listener?.close()
                listener = null
                listening = enabled
                if (enabled) listener = ports.listenForUnlocks(::onUnlock)
                Diag.log(
                    TAG,
                    "dewidebug phoneuse unlock nudge ${if (enabled) "listening for unlocks" else "not listening"}"
                )
            }
        }
    }

    suspend fun sync(trigger: String): Int = syncing.withLock {
        if (!ports.accessGranted()) {
            lastSync = "skipped at ${clock()}: usage access is off ($trigger)"
            Diag.log(TAG, "dewidebug phoneuse sync skipped: usage access is off ($trigger)")
            return 0
        }
        val now = clock().toEpochMilli()
        val from = store.cursor() ?: (now - BACKFILL_MS)
        val raw = ports.usageEvents(from, now)
        val settled = settle(raw, from, now)
        settled.events.forEach(record)
        store.saveCursor(settled.settledUntil)
        lastSync = "at ${clock()} ($trigger): read ${raw.size} usage event(s) from ${Instant.ofEpochMilli(from)}, " +
            "recorded ${settled.events.size}, now settled until ${Instant.ofEpochMilli(settled.settledUntil)}"
        Diag.log(TAG, "dewidebug phoneuse sync $lastSync")
        settled.events.size
    }

    fun nudgeAnswered(what: UnlockNudgeWhat) {
        record(StatEvent.UnlockNudge(clock().toEpochMilli(), what))
        Diag.log(TAG, "dewidebug phoneuse nudge answered: $what")
    }

    private fun onUnlock() {
        val triggeredAt = clock().toEpochMilli()
        scope.launch {
            delay(settleDelayMs)
            runCatching { evaluate(triggeredAt) }
                .onFailure { Diag.warn(TAG, "dewidebug phoneuse could not decide on a nudge", it) }
            runCatching { sync("unlock") }
                .onFailure { Diag.warn(TAG, "dewidebug phoneuse sync after an unlock failed", it) }
        }
    }

    private fun evaluate(triggeredAt: Long) {
        val now = clock()
        if (!ports.accessGranted()) {
            note("not nudging: usage access is off, so earlier unlocks cannot be read", now)
            return
        }
        val raw = ports.usageEvents(now.toEpochMilli() - LOOKBACK_MS, now.toEpochMilli() + 1)
        val foreground = foregroundApp(raw)
        val blocks = blocks(foreground)
        val unlocks = unlockTimes(raw)
        when (val decision = _settings.value.decide(unlocks, triggeredAt, zone(), blocks)) {
            is UnlockNudgeDecision.Skip -> note(
                "not nudging: ${decision.why} [foreground=$foreground unlocks=${unlocks.size}]",
                now
            )
            is UnlockNudgeDecision.Fire -> {
                val request = NudgeRequest(decision.sinceLastMs, decision.unlocksToday, now.toEpochMilli())
                note(
                    "nudging: last unlock ${decision.sinceLastMs / MS_PER_MINUTE} min ago, " +
                        "${decision.unlocksToday} today [foreground=$foreground]",
                    now,
                )
                record(
                    StatEvent.UnlockNudge(
                        now.toEpochMilli(),
                        UnlockNudgeWhat.SHOWN,
                        (decision.sinceLastMs / MS_PER_MINUTE).toInt(),
                        decision.unlocksToday,
                    ),
                )
                ports.showNudge(request)
            }
        }
    }

    private fun blocks(foreground: String?): Set<NudgeBlock> = buildSet {
        if (ports.inCall()) add(NudgeBlock.CALL)
        ports.appBlock(foreground)?.let(::add)
        if (context.exsurgeActive()) add(NudgeBlock.EXSURGE)
        if (context.alarmRinging()) add(NudgeBlock.ALARM)
        if (context.kidLocked()) add(NudgeBlock.KID_LOCK)
        if (foreground == context.ownPackage && context.videoPlaying()) add(NudgeBlock.TOTUM_VIDEO)
    }

    private fun note(text: String, at: Instant) {
        lastDecision = "$text at $at"
        Diag.log(TAG, "dewidebug phoneuse $text")
    }

    val diagnostics: Map<String, String>
        get() = buildMap {
            val settings = _settings.value
            put("phoneUse.usageAccess", ports.accessGranted().toString())
            put("phoneUse.nudgeOn", settings.enabled.toString())
            put("phoneUse.withinMinutes", settings.withinMinutes.toString())
            put("phoneUse.listening", listening.toString())
            put("phoneUse.syncedUntil", store.cursor()?.let(Instant::ofEpochMilli)?.toString() ?: "-")
            put("phoneUse.lastSync", lastSync)
            put("phoneUse.lastDecision", lastDecision)
        }

    companion object {
        const val TAG = "phoneuse"
        private const val SETTLE_MS = 1_500L
        private const val BACKFILL_MS = 7 * 24 * 60 * 60_000L
        private const val LOOKBACK_MS = 24 * 60 * 60_000L
        private const val MS_PER_MINUTE = 60_000L
    }
}
