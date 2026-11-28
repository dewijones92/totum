package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.DevicePower
import com.dewijones92.totum.cli.serve.power.FakeAdb
import com.dewijones92.totum.cli.serve.power.FakeSsapServer
import com.dewijones92.totum.cli.serve.power.OkWebOsDial
import com.dewijones92.totum.cli.serve.power.WakeSender
import com.dewijones92.totum.cli.serve.power.WebOsKey
import com.dewijones92.totum.cli.serve.power.WebOsKeys
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread

class DevicePowersTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tv = FakeSsapServer()
    private val adb = FakeAdb()
    private val logged = Collections.synchronizedList(mutableListOf<String>())
    private val events = Collections.synchronizedList(mutableListOf<String>())
    private var kodiPort = 0
    private var kodiListener: ServerSocket? = null
    private var lgPort = 0
    private var lgListener: ServerSocket? = null
    private lateinit var webosDir: File

    @Before
    fun setUp() {
        webosDir = tmp.newFolder("webos")
        kodiPort = ServerSocket(0).use { it.localPort }
        lgPort = ServerSocket(0).use { it.localPort }
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
        kodiListener?.close()
        lgListener?.close()
        tv.close()
    }

    private fun listening(port: Int): ServerSocket = ServerSocket(port).also { server ->
        thread(isDaemon = true, name = "test-listener-$port") {
            while (!server.isClosed) runCatching { server.accept().close() }
        }
    }

    private val fastTiming = PowerTiming(
        cecAfterWolMs = 50,
        cecSettleMs = 10,
        cecMinGapMs = 60_000,
        cecWakeTimeoutMs = 1_500,
    )

    private val recordingSender = WakeSender { _, address, port -> events += "wol $address:$port" }

    private fun pairTv() {
        WebOsKeys(File(webosDir, "webos-lg-tv.json")).write(WebOsKey(tv.clientKey, null))
    }

    private fun config(
        adbSpec: String? = null,
        apps: String? = null,
        shown: String? = null,
        webos: String? = null,
        timing: PowerTiming = PowerTiming(),
    ) = PowerConfig(
        webos = knownCastsFrom(webos).associateBy { castIdOf(it.name) },
        adb = adbTargetsFrom(adbSpec),
        startApps = startAppsFrom(apps),
        shownOn = shownOnFrom(shown),
        webosDir = webosDir,
        shell = adb,
        dialFor = { OkWebOsDial(listOf(tv.url)) },
        sender = recordingSender,
        timing = timing,
    )

    private var lgMac: String? = null

    private fun devices() = knownDevicesFrom(
        chromecast = emptyList(),
        airplay = emptyList(),
        dlna = listOf(KnownCasts("LG TV", "127.0.0.1:$lgPort", lgMac)),
        kodi = listOf(KnownCasts("Kodi", "127.0.0.1:$kodiPort")),
    )

    private fun powers(config: PowerConfig) = DevicePowers(devices(), config, emptyList())

    private val kodiAdb = "Kodi=192.168.0.211:5555|the Fire Stick"
    private val kodiApp = "Kodi=org.xbmc.kodi/.Splash"

    private suspend fun refreshedTo(power: DevicePower, wanted: TargetPower): TargetPower {
        repeat(REFRESH_TRIES) {
            power.refresh()
            if (power.power == wanted) return power.power
            delay(REFRESH_WAIT_MS)
        }
        return power.power
    }

    private fun DevicePowers.of(name: String) = powerFor(devices().first { it.name == name })

    @Test
    fun `adb targets are read as name=address with an optional place after a bar`() {
        val parsed = adbTargetsFrom("Kodi=192.168.0.211:5555|the Fire Stick; Den = 10.0.0.9:5555 ;Bad=;=nothing")

        assertEquals(setOf("kodi", "den"), parsed.keys)
        assertEquals("192.168.0.211:5555", parsed.getValue("kodi").address)
        assertEquals("the Fire Stick", parsed.getValue("kodi").where)
        assertEquals("its Android device", parsed.getValue("den").where)
    }

    @Test
    fun `an app to start must be a package and an activity`() {
        val parsed = startAppsFrom("Kodi=org.xbmc.kodi/.Splash;Bad=org.xbmc.kodi;Empty=")

        assertEquals(mapOf("kodi" to "org.xbmc.kodi/.Splash"), parsed)
    }

    @Test
    fun `a display is named with an optional input after an at sign`() {
        val parsed = shownOnFrom("Kodi=LG TV@HDMI_1;Den=LG TV;=x")

        assertEquals("LG TV", parsed.getValue("kodi").display)
        assertEquals("HDMI_1", parsed.getValue("kodi").input)
        assertNull(parsed.getValue("den").input)
    }

    @Test
    fun `the environment gives every setting, the key folder under the data dir, and adb unless told otherwise`() {
        val env = mapOf(
            "TOTUM_WEBOS_TARGETS" to "LG TV=192.168.0.219",
            "TOTUM_ADB_TARGETS" to "Kodi=192.168.0.211:5555",
            "TOTUM_START_APPS" to "Kodi=org.xbmc.kodi/.Splash",
            "TOTUM_SHOWN_ON" to "Kodi=LG TV@HDMI_1",
        )

        val config = PowerConfig.fromEnv(env::get, File("/data/totum"))

        assertEquals(setOf("lg-tv"), config.webos.keys)
        assertEquals(setOf("kodi"), config.adb.keys)
        assertEquals(setOf("kodi"), config.startApps.keys)
        assertEquals(setOf("kodi"), config.shownOn.keys)
        assertEquals(File("/data/totum/webos"), config.webosDir)
        assertEquals(File("/data/totum/webos/webos-lg-tv.json"), config.webosKeyFile("lg-tv"))
        assertEquals("adb", config.adbBinary)
        assertEquals(
            "/usr/bin/adb",
            PowerConfig.fromEnv(mapOf("TOTUM_ADB" to "/usr/bin/adb")::get, File("/d")).adbBinary
        )
    }

    @Test
    fun `a device with nothing configured is not managed, so its play is never held`() {
        val powers = powers(config())

        assertNull(powers.of("Kodi"))
        assertNull(powers.of("LG TV"))
    }

    @Test
    fun `a Kodi with adb and an app starts that app when it does not answer, and picking it says it starts an app`() =
        runBlocking {
            val power = powers(
                config(adbSpec = "Kodi=192.168.0.211:5555|the Fire Stick", apps = "Kodi=org.xbmc.kodi/.Splash")
            )
                .of("Kodi")!!
            adb.onStart = { kodiListener = ServerSocket(kodiPort) }

            assertTrue(power.startsApp)
            assertFalse(power.canSwitchOff)
            assertTrue(power.ensureAwake())

            assertTrue(adb.shellLines.contains("input keyevent KEYCODE_WAKEUP"))
            assertTrue(adb.shellLines.contains("am start -n org.xbmc.kodi/.Splash"))
            assertEquals(TargetPower.ON, power.power)
        }

    @Test
    fun `a Kodi with adb but no app to start is not managed`() {
        assertNull(powers(config(adbSpec = "Kodi=192.168.0.211:5555")).of("Kodi"))
    }

    @Test
    fun `a Kodi that is already running is not touched`() = runBlocking {
        kodiListener = ServerSocket(kodiPort)
        val power = powers(
            config(adbSpec = "Kodi=192.168.0.211:5555", apps = "Kodi=org.xbmc.kodi/.Splash")
        ).of("Kodi")!!

        assertTrue(power.ensureAwake())

        assertTrue(adb.commands.isEmpty())
    }

    @Test
    fun `a paired webOS TV can be switched off and a TV that was never paired cannot`() = runBlocking {
        val unpaired = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!
        assertFalse(unpaired.canSwitchOff)
        assertEquals("Totum has no way to switch it off", unpaired.switchOff())

        pairTv()
        val paired = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!
        assertTrue(paired.canSwitchOff)
        assertNull(paired.switchOff())
        assertTrue(tv.uris().contains("ssap://system/turnOff"))
        assertEquals(TargetPower.ASLEEP, paired.power)
    }

    @Test
    fun `a webOS TV is on only while its panel is active`() = runBlocking {
        pairTv()
        val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!

        tv.powerState = "Active Standby"
        assertEquals(TargetPower.ASLEEP, refreshedTo(power, TargetPower.ASLEEP))

        tv.powerState = "Active"
        assertEquals(TargetPower.ON, refreshedTo(power, TargetPower.ON))
    }

    @Test
    fun `a switched off TV stays asleep for a moment even if its port still answers`() = runBlocking {
        pairTv()
        val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!
        power.switchOff()
        tv.powerState = "Active"

        repeat(5) {
            power.refresh()
            delay(REFRESH_WAIT_MS)
        }

        assertEquals(TargetPower.ASLEEP, power.power)
    }

    @Test
    fun `a Kodi shown on a webOS TV puts the TV on its input, and switching it off switches the TV off`() =
        runBlocking {
            pairTv()
            val power = powers(
                config(
                    adbSpec = "Kodi=192.168.0.211:5555|the Fire Stick",
                    apps = "Kodi=org.xbmc.kodi/.Splash",
                    shown = "Kodi=LG TV@HDMI_1",
                    webos = "LG TV=127.0.0.1",
                ),
            ).of("Kodi")!!
            adb.onStart = { kodiListener = ServerSocket(kodiPort) }

            assertTrue(power.canSwitchOff)
            assertTrue(power.ensureAwake())

            val input = tv.requests.first { it["uri"]?.toString()?.contains("tv/switchInput") == true }
            assertTrue(input.toString(), input.toString().contains("HDMI_1"))

            assertNull(power.switchOff())
            assertTrue(tv.uris().contains("ssap://system/turnOff"))
        }

    @Test
    fun `a Kodi shown on a TV leaves the TV's input alone when no input is named`() = runBlocking {
        pairTv()
        kodiListener = ServerSocket(kodiPort)
        val power = powers(
            config(
                adbSpec = "Kodi=192.168.0.211:5555",
                apps = "Kodi=org.xbmc.kodi/.Splash",
                shown = "Kodi=LG TV",
                webos = "LG TV=127.0.0.1",
            ),
        ).of("Kodi")!!

        assertTrue(power.ensureAwake())

        assertFalse(tv.uris().contains("ssap://tv/switchInput"))
    }

    @Test
    fun `two devices shown on each other do not loop, and say so`() {
        val powers = powers(config(shown = "Kodi=LG TV;LG TV=Kodi"))

        powers.of("Kodi")

        assertTrue(logged.toString(), logged.any { it.contains("leads back to itself") })
    }

    @Test
    fun `a setting that names a device that does not exist is warned about, not crashed on`() {
        powers(config(adbSpec = "Ghost=192.168.0.9:5555", apps = "Ghost=a.b/.C"))

        assertTrue(logged.toString(), logged.any { it.contains("\"ghost\", which is not a configured device") })
    }

    @Test
    fun `a power is built once per device`() {
        val powers = powers(config(webos = "LG TV=127.0.0.1"))
        pairTv()

        assertNotNull(powers.of("LG TV"))
        assertTrue(powers.of("LG TV") === powers.of("LG TV"))
    }

    @Test
    fun `a dark paired TV stays asleep when its cast port starts answering, webOS being down`() = runBlocking {
        pairTv()
        tv.close()
        val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!

        assertEquals(TargetPower.ASLEEP, refreshedTo(power, TargetPower.ASLEEP))
        lgListener = listening(lgPort)
        repeat(STEADY_CHECKS) {
            power.refresh()
            delay(REFRESH_WAIT_MS)
            assertEquals("the check flapped to ${power.power}", TargetPower.ASLEEP, power.power)
        }
        assertFalse(logged.toString(), logged.any { it.contains("ASLEEP -> ON (power check)") })
    }

    @Test
    fun `a failed webOS connect while the TV is on and its cast port answers does not read as asleep`() = runBlocking {
        pairTv()
        tv.powerState = "Active"
        lgListener = listening(lgPort)
        val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!
        assertEquals(TargetPower.ON, refreshedTo(power, TargetPower.ON))

        tv.closeNewConnectionsWith = 1008
        repeat(STEADY_CHECKS) {
            power.refresh()
            delay(REFRESH_WAIT_MS)
            assertEquals("the blip read as ${power.power}", TargetPower.ON, power.power)
        }
        tv.closeNewConnectionsWith = null
        repeat(STEADY_CHECKS) {
            power.refresh()
            delay(REFRESH_WAIT_MS)
            assertEquals(TargetPower.ON, power.power)
        }

        assertFalse(logged.toString(), logged.any { it.contains("ON -> ASLEEP") || it.contains("ASLEEP -> ON") })
        val held = logged.filter { it.contains("LG TV: power check unsure") }
        assertEquals(logged.toString(), 1, held.size)
        assertTrue(held.single(), held.single().contains("unknown, maybe on") && held.single().contains("keeping ON"))
    }

    @Test
    fun `webOS unreachable with a silent cast port is asleep, and so is webOS answering standby`() =
        runBlocking {
            pairTv()
            tv.powerState = "Active"
            val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!
            assertEquals(TargetPower.ON, refreshedTo(power, TargetPower.ON))

            tv.closeNewConnectionsWith = 1008
            assertEquals(TargetPower.ASLEEP, refreshedTo(power, TargetPower.ASLEEP))

            tv.closeNewConnectionsWith = null
            tv.powerState = "Active"
            assertEquals(TargetPower.ON, refreshedTo(power, TargetPower.ON))
            lgListener = listening(lgPort)
            tv.powerState = "Suspend"
            assertEquals(TargetPower.ASLEEP, refreshedTo(power, TargetPower.ASLEEP))
        }

    @Test
    fun `a TV that was never paired is judged by its cast port, as before`() = runBlocking {
        lgListener = listening(lgPort)
        val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!

        assertEquals(TargetPower.ON, refreshedTo(power, TargetPower.ON))
        lgListener?.close()
        assertEquals(TargetPower.ASLEEP, refreshedTo(power, TargetPower.ASLEEP))
    }

    @Test
    fun `a power change says what webOS and the cast port answered, once per change`() = runBlocking {
        pairTv()
        lgListener = listening(lgPort)
        val power = powers(config(webos = "LG TV=127.0.0.1")).of("LG TV")!!

        tv.powerState = "Active Standby"
        refreshedTo(power, TargetPower.ASLEEP)
        tv.powerState = "Active"
        refreshedTo(power, TargetPower.ON)
        repeat(3) {
            power.refresh()
            delay(REFRESH_WAIT_MS)
        }

        val asleep = logged.filter { it.contains("LG TV: ON -> ASLEEP (power check)") }
        val on = logged.filter { it.contains("LG TV: ASLEEP -> ON (power check)") }
        assertEquals(logged.toString(), 1, asleep.size)
        assertEquals(logged.toString(), 1, on.size)
        assertTrue(asleep.single(), asleep.single().contains("webOS power state Active Standby"))
        assertTrue(asleep.single(), asleep.single().contains("cast port 127.0.0.1:$lgPort answers"))
        assertTrue(asleep.single(), asleep.single().contains("not used"))
        assertTrue(on.single(), on.single().contains("webOS power state Active"))
    }

    @Test
    fun `a stick that is awake, with the TV it is shown on off, has its sleep and wake cycled so the TV hears it`() =
        runBlocking {
            pairTv()
            tv.powerState = "Active Standby"
            adb.wakefulness = "mWakefulness=Awake"
            adb.onSleepKey = { events += "sleep" }
            adb.onWakeKey = {
                events += "wake"
                tv.powerState = "Active"
            }
            adb.onStart = { kodiListener = ServerSocket(kodiPort) }
            val power = powers(
                config(
                    adbSpec = kodiAdb,
                    apps = kodiApp,
                    shown = "Kodi=LG TV@HDMI_1",
                    webos = "LG TV=127.0.0.1",
                    timing = fastTiming,
                ),
            ).of("Kodi")!!

            assertTrue(power.ensureAwake())

            assertEquals(listOf("sleep", "wake"), events)
            val said = logged.filter { it.contains("Kodi") && it.contains("CEC") }
            assertTrue(logged.toString(), said.any { it.contains("awake") && it.contains("LG TV is not on") })
        }

    @Test
    fun `a stick that is awake with its TV on is left alone`() = runBlocking {
        pairTv()
        tv.powerState = "Active"
        adb.wakefulness = "mWakefulness=Awake"
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = { events += "wake" }
        adb.onStart = { kodiListener = ServerSocket(kodiPort) }
        val power = powers(
            config(
                adbSpec = kodiAdb,
                apps = kodiApp,
                shown = "Kodi=LG TV@HDMI_1",
                webos = "LG TV=127.0.0.1",
                timing = fastTiming,
            ),
        ).of("Kodi")!!

        assertTrue(power.ensureAwake())

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `wake-on-LAN goes first, then with no answer the stick shown on the TV wakes it over CEC`() = runBlocking {
        lgMac = "02:00:00:00:00:01"
        pairTv()
        tv.powerState = "Active Standby"
        adb.wakefulness = "mWakefulness=Awake"
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = {
            events += "wake"
            tv.powerState = "Active"
        }
        val power = powers(
            config(adbSpec = kodiAdb, shown = "Kodi=LG TV@HDMI_1", webos = "LG TV=127.0.0.1", timing = fastTiming),
        ).of("LG TV")!!

        assertTrue(power.ensureAwake())

        assertTrue(events.toString(), events.first().startsWith("wol "))
        assertEquals(listOf("sleep", "wake"), events.filter { it == "sleep" || it == "wake" })
        assertTrue(events.indexOf("sleep") > events.indexOfFirst { it.startsWith("wol ") })
        assertTrue(logged.toString(), logged.any { it.contains("wake-on-LAN") && it.contains("HDMI-CEC") })
        assertNull(power.failure)
    }

    @Test
    fun `a wake-on-LAN that works leaves the stick alone`() = runBlocking {
        lgMac = "02:00:00:00:00:01"
        pairTv()
        tv.powerState = "Active Standby"
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = { events += "wake" }
        val answering = WakeSender { _, address, port ->
            events += "wol $address:$port"
            tv.powerState = "Active"
        }
        val power = DevicePowers(
            devices(),
            PowerConfig(
                webos = knownCastsFrom("LG TV=127.0.0.1").associateBy { castIdOf(it.name) },
                adb = adbTargetsFrom(kodiAdb),
                shownOn = shownOnFrom("Kodi=LG TV@HDMI_1"),
                webosDir = webosDir,
                shell = adb,
                dialFor = { OkWebOsDial(listOf(tv.url)) },
                sender = answering,
                timing = fastTiming,
            ),
            emptyList(),
        ).of("LG TV")!!

        assertTrue(power.ensureAwake())

        assertTrue(events.any { it.startsWith("wol ") })
        assertFalse(events.toString(), events.contains("sleep"))
    }

    @Test
    fun `a TV that stays dark gets one CEC cycle, not one per attempt, while the first may still be working`() =
        runBlocking {
            lgMac = "02:00:00:00:00:01"
            pairTv()
            tv.powerState = "Active Standby"
            adb.onSleepKey = { events += "sleep" }
            adb.onWakeKey = { events += "wake" }
            val power = powers(
                config(adbSpec = kodiAdb, shown = "Kodi=LG TV@HDMI_1", webos = "LG TV=127.0.0.1", timing = fastTiming),
            ).of("LG TV")!!

            assertFalse(power.ensureAwake())
            assertFalse(power.ensureAwake())

            assertEquals(listOf("sleep", "wake"), events.filter { it == "sleep" || it == "wake" })
            assertTrue(logged.toString(), logged.any { it.contains("CEC") && it.contains("skipped") })
        }

    @Test
    fun `a TV whose webOS is down but whose cast port answers may be on, so the stick is not cycled`() = runBlocking {
        lgMac = "02:00:00:00:00:01"
        pairTv()
        tv.close()
        lgListener = listening(lgPort)
        adb.wakefulness = "mWakefulness=Awake"
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = { events += "wake" }
        val power = powers(
            config(adbSpec = kodiAdb, shown = "Kodi=LG TV@HDMI_1", webos = "LG TV=127.0.0.1", timing = fastTiming),
        ).of("LG TV")!!

        power.ensureAwake()

        assertTrue(events.toString(), events.any { it.startsWith("wol ") })
        assertEquals(emptyList<String>(), events.filter { it == "sleep" || it == "wake" })
        assertTrue(
            logged.toString(),
            logged.any { it.contains("CEC nudge not sent") && it.contains("cast port") && it.contains("maybe on") },
        )
    }

    @Test
    fun `an awake stick is not cycled when its TV's webOS is down and the cast port answers`() = runBlocking {
        pairTv()
        tv.close()
        lgListener = listening(lgPort)
        adb.wakefulness = "mWakefulness=Awake"
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = { events += "wake" }
        adb.onStart = { kodiListener = ServerSocket(kodiPort) }
        val power = powers(
            config(
                adbSpec = kodiAdb,
                apps = kodiApp,
                shown = "Kodi=LG TV@HDMI_1",
                webos = "LG TV=127.0.0.1",
                timing = fastTiming,
            ),
        ).of("Kodi")!!

        assertTrue(power.ensureAwake())

        assertEquals(emptyList<String>(), events)
        assertTrue(logged.toString(), logged.any { it.contains("CEC nudge not sent") && it.contains("maybe on") })
    }

    @Test
    fun `a TV that webOS says is on standby, with its cast port answering, is not cycled either`() = runBlocking {
        pairTv()
        tv.powerState = "Active Standby"
        lgListener = listening(lgPort)
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = { events += "wake" }
        val power = powers(
            config(adbSpec = kodiAdb, shown = "Kodi=LG TV@HDMI_1", webos = "LG TV=127.0.0.1", timing = fastTiming),
        ).of("LG TV")!!

        power.ensureAwake()

        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `a TV with a stick shown on it but no wake-on-LAN is woken over CEC straight away`() = runBlocking {
        pairTv()
        tv.powerState = "Active Standby"
        adb.onSleepKey = { events += "sleep" }
        adb.onWakeKey = {
            events += "wake"
            tv.powerState = "Active"
        }
        val power = powers(
            config(adbSpec = kodiAdb, shown = "Kodi=LG TV@HDMI_1", webos = "LG TV=127.0.0.1", timing = fastTiming),
        ).of("LG TV")!!

        assertTrue(power.ensureAwake())

        assertEquals(listOf("sleep", "wake"), events)
    }

    private companion object {
        const val STEADY_CHECKS = 4
        const val REFRESH_TRIES = 100
        const val REFRESH_WAIT_MS = 20L
    }
}
