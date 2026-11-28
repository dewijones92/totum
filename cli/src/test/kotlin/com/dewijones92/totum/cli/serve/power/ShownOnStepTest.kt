package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections

class ShownOnStepTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tv = FakeSsapServer()
    private val logged = Collections.synchronizedList(mutableListOf<String>())
    private var now = 0L

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
        tv.close()
    }

    private val neverWakes = object : WakeStep {
        override val name = "a wake that does nothing"

        override suspend fun run() = true
    }

    private fun display(answers: () -> Boolean, steps: List<WakeStep> = listOf(neverWakes)) = SteppedPower(
        label = "LG TV",
        wakeSteps = steps,
        answers = { answers() },
        wakeFailed = "❌ LG TV didn't wake",
        timeoutMs = 2_000,
        pause = {
            now += it
            yield()
        },
        clock = { now },
    )

    private fun pairedTv(): WebOsTv {
        val keys = WebOsKeys(tmp.newFolder("webos").resolve("webos-lg-tv.json"))
        keys.write(WebOsKey(tv.clientKey, null))
        return WebOsTv("LG TV", OkWebOsDial(listOf(tv.url)), keys)
    }

    private fun said() = logged.toList().joinToString("\n")

    @Test
    fun `a TV that did not come on is not said to be on, in the step's name or in the log`() = runBlocking {
        val step = ShownOnStep(display({ false }), "LG TV", null, "HDMI_1")

        assertFalse(step.run())

        assertFalse(step.name, step.name.contains("LG TV on"))
        assertFalse(said(), said().contains("LG TV on"))
        assertTrue(said(), said().contains("LG TV did not come on"))
    }

    @Test
    fun `a TV that is off with no way to wake it is not claimed on either`() = runBlocking {
        val step = ShownOnStep(display({ false }, steps = emptyList()), "LG TV", null, "HDMI_1")

        assertFalse(step.run())

        assertTrue(said(), said().contains("LG TV did not come on"))
    }

    @Test
    fun `the input is not asked for while the TV is not on, since webOS cannot be asked`() = runBlocking {
        tv.powerState = "Active Standby"
        val step = ShownOnStep(display({ false }), "LG TV", pairedTv(), "HDMI_1")

        assertFalse(step.run())

        assertFalse(tv.uris().toString(), tv.uris().any { it.contains("switchInput") })
        assertTrue(said(), said().contains("input HDMI_1 was not asked for"))
    }

    @Test
    fun `a TV that is on is put on the input and the step says what it did`() = runBlocking {
        val step = ShownOnStep(display({ true }), "LG TV", pairedTv(), "HDMI_1")

        assertTrue(step.run())

        assertEquals(1, tv.uris().count { it.contains("switchInput") })
        assertTrue(said(), said().contains("LG TV is on; switched to input HDMI_1"))
    }
}
