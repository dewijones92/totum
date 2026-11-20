package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KodiStartTest {

    private val adb = FakeAdb()
    private val component = "org.xbmc.kodi/.Splash"
    private var now = 0L
    private var answering = false
    private var answersAfterStart = true
    private val notesSeen = mutableListOf<String?>()
    private lateinit var power: SteppedPower

    private fun wakeSteps(): List<WakeStep> {
        val stick = Adb("192.168.0.211:5555", adb)
        return listOf(
            AdbWakeStick("Kodi", stick),
            AdbStartApp("Kodi", stick, component, "the Fire Stick"),
        )
    }

    private fun build(): SteppedPower = SteppedPower(
        label = "Kodi",
        wakeSteps = wakeSteps(),
        answers = {
            notesSeen += power.note
            answering
        },
        wakeFailed = "❌ Kodi didn't start",
        startsApp = true,
        timeoutMs = AdbStartApp.START_TIMEOUT_MS,
        pause = {
            now += it
            yield()
        },
        clock = { now },
    ).also { power = it }

    @Test
    fun `a Kodi that answers is left alone and no adb command is sent`() = runBlocking {
        build()
        answering = true

        assertTrue(power.ensureAwake())

        assertTrue(adb.commands.isEmpty())
        assertEquals(TargetPower.ON, power.power)
    }

    @Test
    fun `a Kodi that is not answering has its stick woken and Kodi started, in that order`() = runBlocking {
        adb.onStart = { answering = true }
        build()

        assertTrue(power.ensureAwake())

        assertEquals(
            listOf(
                "dumpsys power | grep mWakefulness=",
                "input keyevent KEYCODE_WAKEUP",
                "pidof org.xbmc.kodi || true",
                "am start -n org.xbmc.kodi/.Splash",
            ),
            adb.shellLines,
        )
        assertEquals("each step connects first", 2, adb.commands.count { it[1] == "connect" })
        assertEquals(TargetPower.ON, power.power)
        assertNull(power.failure)
    }

    @Test
    fun `while Kodi starts the phone is told so, in words, and the note goes away once it answers`() = runBlocking {
        adb.onStart = { answering = true }
        build()

        power.ensureAwake()

        assertTrue(notesSeen.toString(), "🔌 Starting Kodi on the Fire Stick…" in notesSeen)
        assertNull(power.note)
        assertTrue(power.startsApp)
    }

    @Test
    fun `a stick that is already awake is not sent the wake key`() = runBlocking {
        adb.wakefulness = "mWakefulness=Awake"
        adb.onStart = { answering = true }
        build()

        power.ensureAwake()

        assertFalse(adb.shellLines.contains("input keyevent KEYCODE_WAKEUP"))
        assertTrue(adb.shellLines.contains("am start -n org.xbmc.kodi/.Splash"))
    }

    @Test
    fun `a stick that is dozing is woken too`() = runBlocking {
        adb.wakefulness = "mWakefulness=Dozing"
        adb.onStart = { answering = true }
        build()

        power.ensureAwake()

        assertTrue(adb.shellLines.contains("input keyevent KEYCODE_WAKEUP"))
    }

    @Test
    fun `a Kodi that never starts fails after a minute and says so`() = runBlocking {
        build()

        assertFalse(power.ensureAwake())

        assertEquals("❌ Kodi didn't start", power.failure)
        assertEquals(TargetPower.ASLEEP, power.power)
        assertTrue("gave up at $now ms", now in 60_000..61_000)
        assertNull(power.note)
    }

    @Test
    fun `a Kodi that is slow to start is started again every 20 seconds until the minute is up`() = runBlocking {
        build()

        power.ensureAwake()

        val starts = adb.shellLines.count { it == "am start -n org.xbmc.kodi/.Splash" }
        assertTrue("started $starts times", starts in 3..4)
    }

    @Test
    fun `adb that cannot connect sends nothing and the wake fails with the same words`() = runBlocking {
        adb.connectSays = "failed to connect to '192.168.0.211:5555'"
        build()

        assertFalse(power.ensureAwake())

        assertTrue(adb.shellLines.isEmpty())
        assertEquals("❌ Kodi didn't start", power.failure)
    }

    @Test
    fun `an already connected adb is not a failure`() = runBlocking {
        adb.connectSays = "already connected to 192.168.0.211:5555"
        adb.onStart = { answering = true }
        build()

        assertTrue(power.ensureAwake())
    }

    @Test
    fun `an am start that errors is not counted as started, and one that says Starting is`() = runBlocking {
        val step = AdbStartApp("Kodi", Adb("192.168.0.211:5555", adb), component, "the Fire Stick")

        adb.startSays = "Error type 3\nError: Activity class {org.xbmc.kodi/.Splash} does not exist."
        assertFalse(step.run())

        adb.startSays = "Starting: Intent { cmp=org.xbmc.kodi/.Splash }"
        assertTrue(step.run())
    }

    @Test
    fun `a stick whose wakefulness cannot be read is still tried with the app start`() = runBlocking {
        adb.wakefulness = null
        adb.onStart = { answering = true }
        build()

        assertTrue(power.ensureAwake())

        assertFalse(adb.shellLines.contains("input keyevent KEYCODE_WAKEUP"))
        assertTrue(adb.shellLines.contains("am start -n org.xbmc.kodi/.Splash"))
    }

    @Test
    fun `it cannot be switched off by itself, only through its display`() {
        build()

        assertFalse(power.canSwitchOff)
    }
}
