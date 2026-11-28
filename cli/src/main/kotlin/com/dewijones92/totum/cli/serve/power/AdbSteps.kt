package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.cli.serve.speakers.Shell
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class Adb(
    private val address: String,
    private val shell: Shell,
    private val binary: String = "adb",
) {
    suspend fun connect(): Boolean {
        val said = shell.run(listOf(binary, "connect", address))?.trim().orEmpty()
        val connected = said.startsWith("connected to") || said.startsWith("already connected")
        if (!connected) {
            Diag.warn("adb", "$address: adb connect said \"${said.take(SAID)}\", so nothing can be sent to it")
        }
        return connected
    }

    suspend fun run(command: String): String? = shell.run(listOf(binary, "-s", address, "shell", command))

    private companion object {
        const val SAID = 200
    }
}

internal class CecNudge(
    private val label: String,
    private val adb: Adb,
    private val settleMs: Long = SETTLE_MS,
    private val minGapMs: Long = MIN_GAP_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()

    @Volatile private var lastSentAtMs: Long? = null

    suspend fun announce(why: String, signOfLife: suspend () -> String? = { null }): Boolean = lock.withLock {
        signOfLife()?.let { sign ->
            Diag.log(
                "adb",
                "$label: CEC nudge not sent ($why): $sign; a sleep would send the TV a CEC standby, so the wake " +
                    "is left to wake-on-LAN alone",
            )
            return@withLock true
        }
        val ago = lastSentAtMs?.let { clock() - it }
        if (ago != null && ago < minGapMs) {
            Diag.log(
                "adb",
                "$label: CEC nudge skipped ($why): one was sent ${ago}ms ago and the TV may still be coming on",
            )
            return@withLock true
        }
        if (!adb.connect()) return@withLock false
        Diag.log(
            "adb",
            "$label: $why; cycling its Android device sleep -> wake so it announces itself to the TV over HDMI-CEC",
        )
        if (adb.run("input keyevent KEYCODE_SLEEP") == null) {
            Diag.warn("adb", "$label: KEYCODE_SLEEP was not accepted, so no CEC nudge")
            return@withLock false
        }
        pause(settleMs)
        if (adb.run("input keyevent KEYCODE_WAKEUP") == null) {
            Diag.warn("adb", "$label: KEYCODE_WAKEUP was not accepted after the sleep; the device may be left asleep")
            return@withLock false
        }
        lastSentAtMs = clock()
        Diag.log("adb", "$label: CEC nudge sent (KEYCODE_SLEEP, ${settleMs}ms, KEYCODE_WAKEUP)")
        true
    }

    companion object {
        const val SETTLE_MS = 6_000L
        const val MIN_GAP_MS = 30_000L
    }
}

internal class ShownTv(val label: String, private val display: DevicePower) {
    suspend fun needsNudge(): Boolean {
        val power = display.power
        if (power == TargetPower.WAKING) return false
        return !display.answersNow()
    }

    suspend fun signOfLife(): String? = display.signOfLife()
}

internal class CecWakeStep(
    private val tvLabel: String,
    private val stickLabel: String,
    private val nudge: CecNudge,
    override val startsAfterMs: Long,
    private val signOfLife: suspend () -> String? = { null },
) : WakeStep {
    override val name: String = "wake $tvLabel over HDMI-CEC through $stickLabel (adb sleep -> wake)"

    override val note: String = "📺 Waking $tvLabel through $stickLabel…"

    override suspend fun run(): Boolean = nudge.announce("$tvLabel did not answer the wake-on-LAN", signOfLife)
}

internal class AdbWakeStick(
    private val label: String,
    private val adb: Adb,
    private val nudge: CecNudge? = null,
    private val shownOn: ShownTv? = null,
) : WakeStep {
    override val name: String = "wake $label's Android device (KEYCODE_WAKEUP over adb)"

    override suspend fun run(): Boolean {
        if (!adb.connect()) return false
        val power = adb.run("dumpsys power | grep mWakefulness=")?.trim()
        if (power == null) {
            Diag.warn("adb", "$label: could not read whether its Android device is awake")
            return false
        }
        if (!power.contains(ASLEEP, ignoreCase = true) && !power.contains(DOZING, ignoreCase = true)) {
            if (nudge != null && shownOn != null && shownOn.needsNudge()) {
                Diag.log(
                    "adb",
                    "$label: its Android device is already awake ($power), but ${shownOn.label} is not on, so it " +
                        "has sent no HDMI-CEC 'image view on'",
                )
                return nudge.announce(
                    "${shownOn.label} is not on although $label's Android device is awake",
                    shownOn::signOfLife,
                )
            }
            Diag.log("adb", "$label: its Android device is already awake ($power), no wake key sent")
            return true
        }
        Diag.log("adb", "$label: its Android device is asleep ($power); sending KEYCODE_WAKEUP")
        adb.run("input keyevent KEYCODE_WAKEUP") ?: return false
        return true
    }

    private companion object {
        const val ASLEEP = "Asleep"
        const val DOZING = "Dozing"
    }
}

internal class AdbStartApp(
    private val label: String,
    private val adb: Adb,
    private val component: String,
    private val where: String,
) : WakeStep {
    private val packageName = component.substringBefore('/')

    override val name: String = "start $label ($component) over adb"

    override val note: String = "🔌 Starting $label on $where…"

    override val repeatsEveryMs: Long = RESTART_MS

    override suspend fun run(): Boolean {
        if (!adb.connect()) return false
        val running = adb.run("pidof $packageName || true")?.trim().orEmpty()
        Diag.log(
            "adb",
            "$label: $packageName is ${if (running.isEmpty()) "not running" else "running as pid $running"}; " +
                "starting $component",
        )
        val said = adb.run("am start -n $component")?.trim()
        if (said == null || said.contains("Error", ignoreCase = true)) {
            Diag.warn("adb", "$label: am start said \"${said?.take(SAID).orEmpty()}\"")
            return false
        }
        Diag.log("adb", "$label: am start said \"${said.take(SAID)}\"")
        return true
    }

    companion object {
        const val RESTART_MS = 20_000L
        const val START_TIMEOUT_MS = 60_000L
        private const val SAID = 200
    }
}
