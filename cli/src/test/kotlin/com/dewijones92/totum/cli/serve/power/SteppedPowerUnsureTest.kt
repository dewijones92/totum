package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SteppedPowerUnsureTest {

    private var now = 0L
    private var unsure = true
    private var answers = false
    private val ran = mutableListOf<String>()
    private val logged = Collections.synchronizedList(mutableListOf<String>())

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private fun step(label: String, after: Long = 0) = object : WakeStep {
        override val name = label
        override val startsAfterMs = after

        override suspend fun run(): Boolean {
            ran += "$label@$now"
            return true
        }
    }

    private fun power(vararg steps: WakeStep) = SteppedPower(
        label = "LG TV",
        wakeSteps = steps.toList(),
        answers = { answers },
        wakeFailed = "did not wake",
        evidence = { "webOS unavailable (blip); cast port 192.168.0.219:8009 answers" },
        unsure = { unsure },
        pause = {
            now += it
            yield()
        },
        clock = { now },
    )

    @Test
    fun `a play to a TV that may be on sends the wake-on-LAN and goes on at once without waiting for webOS`() =
        runBlocking {
            val wake = power(step("wol"), step("cec", after = 5_000))

            assertTrue(wake.ensureAwake())

            assertEquals(listOf("wol@0"), ran)
            assertEquals("no wait for webOS", 0L, now)
            assertTrue(wake.sentWake)
            assertNull(wake.failure)
            assertFalse(wake.power == TargetPower.WAKING)
            val decision = logged.single { it.contains("unknown, maybe on") }
            assertTrue(decision, decision.contains("sending wol") && decision.contains("without waiting for webOS"))
            assertTrue(decision, decision.contains("cast port 192.168.0.219:8009 answers"))
        }

    @Test
    fun `a TV that is really off is woken and waited for as before`() = runBlocking {
        unsure = false
        val wake = power(step("wol"))

        assertFalse(wake.ensureAwake())

        assertEquals("wol@0", ran.first())
        assertTrue("waited out the timeout", now >= SteppedPower.WAKE_TIMEOUT_MS)
        assertEquals(TargetPower.ASLEEP, wake.power)
        assertEquals("did not wake", wake.failure)
    }

    @Test
    fun `a TV that answers is neither woken nor treated as unsure`() = runBlocking {
        answers = true
        val wake = power(step("wol"))

        assertTrue(wake.ensureAwake())

        assertTrue(ran.isEmpty())
        assertEquals(TargetPower.ON, wake.power)
        assertFalse(logged.toString(), logged.any { it.contains("unknown, maybe on") })
    }
}
