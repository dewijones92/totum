package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayRoutesTest {

    @Test
    fun `the route recorded for an item is the one read back, whatever scrolled out of the trail`() {
        PlayRoutes.record("an-item", "a direct url")

        assertEquals("a direct url", PlayRoutes.of("an-item"))
        assertNull(PlayRoutes.of("never-played"))
    }

    @Test
    fun `a later play of the same item replaces its route`() {
        PlayRoutes.record("replayed", "a direct url")
        PlayRoutes.record("replayed", "a local file")

        assertEquals("a local file", PlayRoutes.of("replayed"))
    }

    @Test
    fun `only the most recent items are kept`() {
        (1..200).forEach { PlayRoutes.record("bounded-$it", "hls") }

        assertNull(PlayRoutes.of("bounded-1"))
        assertEquals("hls", PlayRoutes.of("bounded-200"))
    }
}
