package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class SteppedPowerDeferredStepTest {

    private var now = 0L
    private val ran = mutableListOf<String>()
    private val logged = Collections.synchronizedList(mutableListOf<String>())

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private fun step(label: String, after: Long = 0, every: Long? = null) = object : WakeStep {
        override val name = label
        override val startsAfterMs = after
        override val repeatsEveryMs = every

        override suspend fun run(): Boolean {
            ran += "$label@$now"
            return true
        }
    }

    private fun power(answerAtMs: Long?, steps: List<WakeStep>) = SteppedPower(
        label = "LG TV",
        wakeSteps = steps,
        answers = { answerAtMs != null && now >= answerAtMs },
        wakeFailed = "did not wake",
        pause = {
            now += it
            yield()
        },
        clock = { now },
    )

    @Test
    fun `a step that starts after silence is held back until the device has been quiet that long`() = runBlocking {
        val wake = power(null, listOf(step("wol", every = 5_000), step("cec", after = 5_000)))

        assertFalse(wake.ensureAwake())

        assertEquals("wol@0", ran.first())
        assertEquals(listOf("cec@5000"), ran.filter { it.startsWith("cec") })
    }

    @Test
    fun `a step that starts after silence is never run when the device answers before then`() = runBlocking {
        val wake = power(3_000, listOf(step("wol", every = 5_000), step("cec", after = 5_000)))

        assertTrue(wake.ensureAwake())

        assertEquals(listOf("wol@0"), ran)
    }

    @Test
    fun `the log says the step was held back, and which silence led to it`() = runBlocking {
        val wake = power(null, listOf(step("wol", every = 5_000), step("cec", after = 5_000)))

        wake.ensureAwake()

        val said = logged.toList().joinToString("\n")
        assertTrue(said, said.contains("waking it (wol, cec after 5000ms of silence)"))
        assertTrue(said, said.contains("silent 5000ms after the wake, now trying cec"))
    }
}
