package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.DevicePower
import com.dewijones92.totum.cli.serve.power.FakeAdb
import com.dewijones92.totum.cli.serve.power.FakeSsapServer
import com.dewijones92.totum.cli.serve.power.OkWebOsDial
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

class DevicePowersTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tv = FakeSsapServer()
    private val adb = FakeAdb()
    private val logged = Collections.synchronizedList(mutableListOf<String>())
    private var kodiPort = 0
    private var kodiListener: ServerSocket? = null
    private lateinit var webosDir: File

    @Before
    fun setUp() {
        webosDir = tmp.newFolder("webos")
        kodiPort = ServerSocket(0).use { it.localPort }
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
        kodiListener?.close()
        tv.close()
    }

    private fun pairTv() {
        WebOsKeys(File(webosDir, "webos-lg-tv.json")).write(WebOsKey(tv.clientKey, null))
    }

    private fun config(
        adbSpec: String? = null,
        apps: String? = null,
        shown: String? = null,
        webos: String? = null,
    ) = PowerConfig(
        webos = knownCastsFrom(webos).associateBy { castIdOf(it.name) },
        adb = adbTargetsFrom(adbSpec),
        startApps = startAppsFrom(apps),
        shownOn = shownOnFrom(shown),
        webosDir = webosDir,
        shell = adb,
        dialFor = { OkWebOsDial(listOf(tv.url)) },
    )

    private fun devices() = knownDevicesFrom(
        chromecast = listOf(KnownCasts("LG TV", "127.0.0.1")),
        airplay = emptyList(),
        dlna = emptyList(),
        kodi = listOf(KnownCasts("Kodi", "127.0.0.1:$kodiPort")),
    )

    private fun powers(config: PowerConfig) = DevicePowers(devices(), config, emptyList())

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
        assertEquals("/usr/bin/adb", PowerConfig.fromEnv(mapOf("TOTUM_ADB" to "/usr/bin/adb")::get, File("/d")).adbBinary)
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
            val power = powers(config(adbSpec = "Kodi=192.168.0.211:5555|the Fire Stick", apps = "Kodi=org.xbmc.kodi/.Splash"))
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
        val power = powers(config(adbSpec = "Kodi=192.168.0.211:5555", apps = "Kodi=org.xbmc.kodi/.Splash")).of("Kodi")!!

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
    fun `a Kodi shown on a webOS TV has the TV put on its input once Kodi answers, and is switched off by switching the TV off`() =
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

    private companion object {
        const val REFRESH_TRIES = 100
        const val REFRESH_WAIT_MS = 20L
    }
}
