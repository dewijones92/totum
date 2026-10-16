package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThrottledChangesTest {

    @Test
    fun `a flapping reason is logged once a minute with the flips it folded in`() {
        val changes = ThrottledChanges(minGapMs = 60_000)

        assertEquals(0, changes.offer("not flat", 0))
        assertNull("the same reason again", changes.offer("not flat", 1_000))
        assertNull(changes.offer("flat for", 2_000))
        assertNull(changes.offer("not flat", 3_000))
        assertNull(changes.offer("flat for", 4_000))
        assertEquals(3, changes.offer("not flat", 61_000))
        assertNull("the same reason again", changes.offer("not flat", 200_000))
        assertEquals(0, changes.offer("flat for", 200_001))
    }
}
