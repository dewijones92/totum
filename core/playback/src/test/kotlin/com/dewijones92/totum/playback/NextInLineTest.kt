package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextInLineTest {

    private fun armed(id: String, uri: String = "https://x/$id", audio: String? = null) =
        NextInLine.Armed(ItemContext.NONE.copy(id = id), uri, audio)

    @Test
    fun `crossing to the armed item hands it over and leaves nothing armed`() {
        val line = NextInLine()
        line.arm(armed("b"))

        assertEquals("b", line.crossedTo("b")?.context?.id)
        assertNull(line.armed)
    }

    @Test
    fun `crossing to something else is not the armed item`() {
        val line = NextInLine()
        line.arm(armed("b"))

        assertNull(line.crossedTo("c"))
        assertEquals("b", line.armed?.context?.id)
    }

    @Test
    fun `the queue's play of the same stream is adopted once`() {
        val line = NextInLine()
        line.arm(armed("b", audio = "https://x/b-audio"))
        line.crossedTo("b")

        assertTrue(line.adopt("b", "https://x/b", "https://x/b-audio"))
        assertFalse(line.adopt("b", "https://x/b", "https://x/b-audio"))
    }

    @Test
    fun `a play of a different stream of the same item is rebuilt, not adopted`() {
        val line = NextInLine()
        line.arm(armed("b"))
        line.crossedTo("b")

        assertFalse(line.adopt("b", "https://x/b-720p", null))
    }

    @Test
    fun `nothing is adopted when the player never crossed`() {
        val line = NextInLine()
        line.arm(armed("b"))

        assertFalse(line.adopt("b", "https://x/b", null))
    }

    @Test
    fun `a rebuild drops what was armed and what could be adopted`() {
        val line = NextInLine()
        line.arm(armed("b"))
        line.forgetOnRebuild("a")

        assertNull(line.armed)
        assertNull(line.crossedTo("b"))
    }

    @Test
    fun `disarming says what was armed, and disarming nothing says nothing`() {
        val line = NextInLine()
        line.arm(armed("b"))

        assertEquals("b", line.disarm("queue changed")?.context?.id)
        assertNull(line.disarm("again"))
    }
}
