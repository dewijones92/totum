package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class CecNudgeTest {

    private val adb = FakeAdb()
    private var now = 1_000_000L
    private val pauses = mutableListOf<Long>()
    private val logged = Collections.synchronizedList(mutableListOf<String>())

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private val keys: List<String> get() = adb.shellLines.filter { it.startsWith("input keyevent") }

    private fun nudge() = CecNudge(
        "Kodi",
        Adb("192.168.0.211:5555", adb),
        settleMs = 6_000,
        minGapMs = 30_000,
        pause = { pauses += it },
        clock = { now },
    )

    @Test
    fun `it puts the stick to sleep, waits six seconds, then wakes it, which is what lit the real TV`() = runBlocking {
        assertTrue(nudge().announce("LG TV is not on"))

        assertEquals(listOf("input keyevent KEYCODE_SLEEP", "input keyevent KEYCODE_WAKEUP"), keys)
        assertEquals(listOf(6_000L), pauses)
        val said = logged.toList().joinToString("\n")
        assertTrue(said, said.contains("Kodi: LG TV is not on; cycling its Android device sleep -> wake"))
        assertTrue(said, said.contains("Kodi: CEC nudge sent"))
    }

    @Test
    fun `a second nudge soon after is skipped, since the first may still be bringing the TV on`() = runBlocking {
        val nudge = nudge()
        nudge.announce("first")

        now += 10_000
        assertTrue(nudge.announce("second"))

        assertEquals("one cycle only", 2, keys.size)
        assertTrue(
            logged.toString(),
            logged.any { it.contains("CEC nudge skipped (second)") && it.contains("10000ms") }
        )
    }

    @Test
    fun `a nudge after the gap goes again`() = runBlocking {
        val nudge = nudge()
        nudge.announce("first")

        now += 31_000
        nudge.announce("second")

        assertEquals(4, keys.size)
    }

    @Test
    fun `adb that cannot connect sends no key and says it did not work`() = runBlocking {
        adb.connectSays = "failed to connect to '192.168.0.211:5555'"

        assertFalse(nudge().announce("LG TV is not on"))

        assertTrue(keys.isEmpty())
    }

    @Test
    fun `a sleep key that fails is not followed by a wake key, and the failure does not start the gap`() = runBlocking {
        adb.keyEventSays = null
        val nudge = nudge()

        assertFalse(nudge.announce("first"))
        assertEquals(listOf("input keyevent KEYCODE_SLEEP"), keys)

        adb.keyEventSays = ""
        now += 1_000
        assertTrue(nudge.announce("second"))
        assertEquals(3, keys.size)
    }
}
