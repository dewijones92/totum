package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.cli.serve.speakers.Shell
import com.dewijones92.totum.common.Diag

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

internal class AdbWakeStick(private val label: String, private val adb: Adb) : WakeStep {
    override val name: String = "wake $label's Android device (KEYCODE_WAKEUP over adb)"

    override suspend fun run(): Boolean {
        if (!adb.connect()) return false
        val power = adb.run("dumpsys power | grep mWakefulness=")?.trim()
        if (power == null) {
            Diag.warn("adb", "$label: could not read whether its Android device is awake")
            return false
        }
        if (!power.contains(ASLEEP, ignoreCase = true) && !power.contains(DOZING, ignoreCase = true)) {
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
