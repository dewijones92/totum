package com.dewijones92.totum.support

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioRecoveryTest {

    private class Device(private val comesBackOnEnableNumber: Int?) {
        var enables = 0
        var disables = 0
        var clockMs = 0L
        val log = mutableListOf<String>()

        private var online = false

        fun recovery() = RadioRecovery(
            enable = {
                enables++
                if (comesBackOnEnableNumber == enables) online = true
            },
            disable = {
                disables++
                online = false
            },
            hasNetwork = { online },
            pause = { clockMs += it },
            log = { log += it },
        )
    }

    @Test
    fun aNetworkThatReturnsStraightAwayCostsNoSecondAttempt() {
        val device = Device(comesBackOnEnableNumber = 1)

        assertTrue(device.recovery().bringBack())

        assertEquals(1, device.enables)
        assertEquals(0, device.disables)
    }

    @Test
    fun aNetworkThatDoesNotComeBackIsSwitchedOffAndOnAgain() {
        val device = Device(comesBackOnEnableNumber = 2)

        assertTrue(
            "the 2026-11-25 failure: Wi-Fi was switched on 1.3 s after being switched off, never reconnected, " +
                "and a single wait was all the recovery there was",
            device.recovery().bringBack(),
        )

        assertEquals(2, device.enables)
        assertEquals("the radios are cycled once between the two attempts", 1, device.disables)
    }

    @Test
    fun aNetworkThatNeverReturnsGivesUpAfterEveryAttemptAndSaysSo() {
        val device = Device(comesBackOnEnableNumber = null)

        assertFalse(device.recovery().bringBack())

        assertEquals(RadioRecovery.ATTEMPTS, device.enables)
        assertEquals(RadioRecovery.ATTEMPTS - 1, device.disables)
        assertEquals(RadioRecovery.ATTEMPTS, device.log.count { "no network within" in it })
        assertTrue(
            "it waited for the whole of every attempt, in the fake clock",
            device.clockMs >= RadioRecovery.ATTEMPTS * RadioRecovery.WAIT_MS,
        )
    }
}
