package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.cli.serve.speakers.Shell
import java.util.Collections

internal class FakeAdb(private val address: String = "192.168.0.211:5555") : Shell {

    @Volatile var connectSays: String? = "connected to $address"

    @Volatile var wakefulness: String? = "mWakefulness=Asleep"

    @Volatile var runningPid: String = ""

    @Volatile var startSays: String? = "Starting: Intent { cmp=org.xbmc.kodi/.Splash }"

    @Volatile var onWakeKey: () -> Unit = {}

    @Volatile var onSleepKey: () -> Unit = {}

    @Volatile var keyEventSays: String? = ""

    @Volatile var onStart: () -> Unit = {}

    val commands: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())

    val shellLines: List<String>
        get() = commands.filter { it.getOrNull(1) == "-s" }.map { it.last() }

    override suspend fun run(args: List<String>): String? {
        commands += args
        if (args.getOrNull(1) == "connect") return connectSays
        check(args.getOrNull(2) == address && args.getOrNull(3) == "shell") { "adb was not aimed at $address: $args" }
        val line = args.last()
        return when {
            line.startsWith("dumpsys power") -> wakefulness
            line == "input keyevent KEYCODE_WAKEUP" -> {
                onWakeKey()
                keyEventSays
            }
            line == "input keyevent KEYCODE_SLEEP" -> {
                onSleepKey()
                keyEventSays
            }
            line.startsWith("pidof ") -> runningPid
            line.startsWith("am start ") -> {
                onStart()
                startSays
            }
            else -> error("an adb command this fake does not know: $line")
        }
    }
}
