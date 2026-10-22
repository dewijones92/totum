package com.dewijones92.totum.kidlock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidLockTest {

    @Test
    fun `unlocking forgets the system pin, so the next lock starts by asking again`() {
        val lock = KidLock()
        lock.lock("player")
        lock.systemPinned(true, "pinned")
        assertTrue(lock.locked.value && lock.pinnedBySystem.value)

        lock.unlock("unpinned")

        assertFalse(lock.locked.value)
        assertFalse(lock.pinnedBySystem.value)
    }
}
