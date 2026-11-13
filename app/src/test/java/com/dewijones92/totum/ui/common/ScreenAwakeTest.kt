package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAwakeTest {

    @Test
    fun `a playing video keeps the screen on on battery or charger, full screen or not`() {
        for (fullscreen in listOf(true, false)) {
            for (charging in listOf(true, false)) {
                assertTrue(screenAwakeDecision(playing = true, fullscreen = fullscreen, charging = charging).keepOn)
            }
        }
    }

    @Test
    fun `paused in full screen the screen stays on only while charging`() {
        assertTrue(screenAwakeDecision(playing = false, fullscreen = true, charging = true).keepOn)
        assertFalse(screenAwakeDecision(playing = false, fullscreen = true, charging = false).keepOn)
    }

    @Test
    fun `paused outside full screen the screen may sleep even on the charger`() {
        assertFalse(screenAwakeDecision(playing = false, fullscreen = false, charging = true).keepOn)
        assertFalse(screenAwakeDecision(playing = false, fullscreen = false, charging = false).keepOn)
    }

    @Test
    fun `every decision says why`() {
        val reasons = listOf(true, false).flatMap { playing ->
            listOf(true, false).flatMap { fullscreen ->
                listOf(true, false).map { charging -> screenAwakeDecision(playing, fullscreen, charging).reason }
            }
        }
        assertTrue(reasons.all { it.isNotBlank() })
        assertEquals(4, reasons.toSet().size)
    }

    @Test
    fun `any plugged power source counts as charging, unplugged does not`() {
        assertTrue(isPluggedIn(plugged = 1))
        assertTrue(isPluggedIn(plugged = 2))
        assertTrue(isPluggedIn(plugged = 4))
        assertFalse(isPluggedIn(plugged = 0))
    }
}
