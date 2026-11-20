package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartWatchTest {

    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val silent = mutableListOf<Pair<String, Long>>()

    private val watch = SessionStartWatch({ delay, run -> timers += delay to run }, 5_000) { device, waited ->
        silent += device to waited
    }

    private fun settle() = timers.toList().also { timers.clear() }.forEach { it.second() }

    @Test
    fun `a route selected and then nothing for five seconds is a cast that did not start`() {
        watch.routeSelected("Kitchen TV")

        assertEquals(5_000L, timers.single().first)
        settle()

        assertEquals(listOf("Kitchen TV" to 5_000L), silent)
    }

    @Test
    fun `any session callback in time means the framework is alive`() {
        watch.routeSelected("Kitchen TV")
        watch.sessionCallback()

        settle()

        assertTrue(silent.isEmpty())
    }

    @Test
    fun `a second selection restarts the wait and the first is forgotten`() {
        watch.routeSelected("Kitchen TV")
        watch.routeSelected("Living Room TV")

        settle()

        assertEquals(listOf("Living Room TV"), silent.map { it.first })
    }

    @Test
    fun `it is said once per selection`() {
        watch.routeSelected("Kitchen TV")
        settle()
        settle()

        assertEquals(1, silent.size)
    }
}
