package com.dewijones92.totum.cli.serve.speakers

import com.dewijones92.totum.cli.serve.targets.TargetKeeper
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.KeepAwake
import kotlinx.coroutines.sync.Mutex
import java.time.LocalTime
import java.time.format.DateTimeParseException

internal enum class KeepAwakeSignal(val lavfi: String) {
    SILENCE("anullsrc=channel_layout=stereo:sample_rate=44100"),
    NOISE("anoisesrc=color=brown:amplitude=0.002:sample_rate=44100"),
}

internal data class KeepAwakeWindow(val from: LocalTime? = null, val until: LocalTime? = null) {
    fun covers(now: LocalTime): Boolean = when {
        from == null || until == null -> true
        from <= until -> now >= from && now < until
        else -> now >= from || now < until
    }

    override fun toString(): String =
        if (from == null || until == null) "always" else "$from-$until"
}

internal class KeepAwakeSettings(
    private val windows: Map<String, KeepAwakeWindow>,
    val signal: KeepAwakeSignal = KeepAwakeSignal.SILENCE,
) {
    val any: Boolean get() = windows.isNotEmpty()

    fun windowFor(id: String, label: String): KeepAwakeWindow? = windows[id.lowercase()] ?: windows[label.lowercase()]

    companion object {
        val NONE = KeepAwakeSettings(emptyMap())

        fun parse(spec: String?, signal: String? = null): KeepAwakeSettings {
            val windows = spec.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.associate { entry ->
                val name = entry.substringBefore('@').trim().lowercase()
                name to windowOf(name, entry.substringAfter('@', "").trim())
            }
            val chosen = signal?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
                KeepAwakeSignal.entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
                    ?: KeepAwakeSignal.SILENCE.also {
                        Diag.warn("speakers", "keep-awake signal \"$wanted\" is not silence or noise; using silence")
                    }
            } ?: KeepAwakeSignal.SILENCE
            val named = windows.entries.joinToString { "${it.key} (${it.value})" }.ifEmpty { "no speaker" }
            Diag.log("speakers", "keep awake is on for $named, signal=$chosen")
            return KeepAwakeSettings(windows, chosen)
        }

        private fun windowOf(name: String, text: String): KeepAwakeWindow {
            if (text.isEmpty()) return KeepAwakeWindow()
            return try {
                KeepAwakeWindow(
                    LocalTime.parse(text.substringBefore('-').trim()),
                    LocalTime.parse(text.substringAfter('-').trim())
                )
            } catch (e: DateTimeParseException) {
                Diag.warn("speakers", "keep awake for $name: \"$text\" is not HH:mm-HH:mm, so it is on all day", e)
                KeepAwakeWindow()
            }
        }
    }
}

internal interface KeepAwakeTransport {
    suspend fun linked(): Boolean

    suspend fun reconnect(): Boolean

    suspend fun sendSignal(signal: KeepAwakeSignal): Boolean
}

internal class KeepAwakeKeeper(
    private val id: String,
    private val window: KeepAwakeWindow,
    private val transport: KeepAwakeTransport,
    private val signal: KeepAwakeSignal = KeepAwakeSignal.SILENCE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val timeOfDay: () -> LocalTime = { LocalTime.now() },
    private val checkEveryMs: Long = CHECK_EVERY_MS,
    private val signalEveryMs: Long = SIGNAL_EVERY_MS,
    private val reconnectEveryMs: Long = RECONNECT_EVERY_MS,
    private val reconnectMaxMs: Long = RECONNECT_MAX_MS,
    private val summaryEvery: Int = SUMMARY_EVERY,
) : TargetKeeper {
    private val lock = Mutex()
    private var lastCheckMs: Long? = null
    private var lastSignalMs: Long? = null
    private var lastReconnectMs: Long? = null
    private var reconnectWaitMs = reconnectEveryMs
    private var checks = 0
    private var signals = 0
    private var signalFailures = 0
    private var reconnects = 0
    private var reconnectFailures = 0
    private var waits = 0
    private var busyChecks = 0

    @Volatile
    override var state: KeepAwake? = null
        private set

    override suspend fun beat(busy: Boolean): Boolean {
        if (!lock.tryLock()) return false
        try {
            val now = clock()
            val last = lastCheckMs
            if (last != null && now - last < checkEveryMs) return false
            lastCheckMs = now
            checks++
            check(busy, now)
            if (checks % summaryEvery == 0) summarise()
            return true
        } finally {
            lock.unlock()
        }
    }

    private suspend fun check(busy: Boolean, now: Long) {
        val time = timeOfDay()
        if (!window.covers(time)) {
            move(KeepAwake.OUTSIDE_WINDOW, "it is $time, outside the window $window, so nothing is sent")
            return
        }
        if (!transport.linked()) {
            reconnect(now)
            return
        }
        reconnectWaitMs = reconnectEveryMs
        move(KeepAwake.KEPT, "connected and kept awake (window $window)")
        if (busy) {
            busyChecks++
            return
        }
        val sentAt = lastSignalMs
        if (sentAt == null || now - sentAt >= signalEveryMs) sendSignal(now)
    }

    private suspend fun reconnect(now: Long) {
        val tried = lastReconnectMs
        if (tried != null && now - tried < reconnectWaitMs) {
            waits++
            move(KeepAwake.RECONNECTING, "not connected; next reconnect in ${reconnectWaitMs - (now - tried)}ms")
            return
        }
        lastReconnectMs = now
        if (transport.reconnect()) {
            reconnects++
            reconnectWaitMs = reconnectEveryMs
            move(KeepAwake.KEPT, "was not connected, reconnected and kept awake (window $window)")
            sendSignal(now)
        } else {
            reconnectFailures++
            reconnectWaitMs = (reconnectWaitMs * 2).coerceAtMost(reconnectMaxMs)
            move(
                KeepAwake.RECONNECTING,
                "not connected and a reconnect failed; the next try is in ${reconnectWaitMs}ms"
            )
        }
    }

    private suspend fun sendSignal(now: Long) {
        lastSignalMs = now
        if (transport.sendSignal(signal)) signals++ else signalFailures++
    }

    private fun move(to: KeepAwake, why: String) {
        if (state == to) return
        Diag.log("speakers", "$id: keep awake $state -> $to: $why")
        state = to
    }

    private fun summarise() {
        Diag.log(
            "speakers",
            "$id: keep awake state=$state after $checks check(s): $signals $signal sent ($signalFailures failed), " +
                "$reconnects reconnect(s) ($reconnectFailures failed, $waits waiting), $busyChecks while playing",
        )
    }

    private companion object {
        const val CHECK_EVERY_MS = 30_000L
        const val SIGNAL_EVERY_MS = 60_000L
        const val RECONNECT_EVERY_MS = 120_000L
        const val RECONNECT_MAX_MS = 600_000L
        const val SUMMARY_EVERY = 20
    }
}
