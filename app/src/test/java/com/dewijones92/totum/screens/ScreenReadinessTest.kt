package com.dewijones92.totum.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenReadinessTest {

    private val changes = mutableListOf<Boolean>()
    private val readiness = ScreenReadiness { ready, _ -> changes += ready }

    @Test
    fun `ready only while charging and on Wi-Fi, told once per change`() {
        readiness.charging(true)
        readiness.wifi(true)
        readiness.wifi(true)
        readiness.charging(false)
        readiness.charging(true)

        assertEquals(listOf(false, true, false, true), changes)
    }

    @Test
    fun `the report says why it is or is not ready`() {
        readiness.charging(true)
        readiness.wifi(false)

        assertEquals(
            mapOf("screen.ready" to "false", "screen.charging" to "true", "screen.wifi" to "false"),
            readiness.diagnostics(),
        )
    }
}
