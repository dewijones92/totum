package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartLatencyTest {

    private var now = 100_000L
    private val latency = StartLatency(clock = { now })

    @Test
    fun `a start with no tap behind it reports time since play`() {
        PlaybackIntent.take(now, Long.MAX_VALUE)
        latency.played("v1")
        now += 1_800
        assertEquals("first sound for v1 1800ms after play()", latency.playing("v1"))
        now += 400
        assertEquals("first picture for v1 2200ms after play()", latency.firstFrame("v1"))
    }

    @Test
    fun `a switch the user asked for is timed from the tap, including the resolve before play`() {
        PlaybackIntent.mark("switch to video (row)", atMs = now)
        now += 4_100
        latency.played("v2")
        now += 1_130
        assertEquals(
            "first picture for v2 1130ms after play(), 5230ms after \"switch to video (row)\" (play() came 4100ms in)",
            latency.firstFrame("v2"),
        )
    }

    @Test
    fun `each is said once per play, and only for the item played`() {
        latency.played("v3")
        assertNull(latency.playing("other"))
        latency.playing("v3")
        assertNull("a resume after a pause is not a start", latency.playing("v3"))
        latency.played("v3")
        assertEquals("first sound for v3 0ms after play()", latency.playing("v3"))
    }

    @Test
    fun `a stale tap is not blamed for a later start, and a tap is used once`() {
        PlaybackIntent.mark("watch (player)", atMs = now)
        now += 61_000
        latency.played("v4")
        assertEquals("first sound for v4 0ms after play()", latency.playing("v4"))
        PlaybackIntent.mark("watch (player)", atMs = now)
        latency.played("v5")
        latency.played("v6")
        assertEquals("first sound for v6 0ms after play()", latency.playing("v6"))
    }
}
