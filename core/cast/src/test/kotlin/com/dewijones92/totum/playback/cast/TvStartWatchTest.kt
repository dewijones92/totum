package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TvStartWatchTest {

    private var clock = 1_000L
    private val timers = mutableListOf<Pair<Long, () -> Unit>>()
    private val stuck = mutableListOf<Triple<String, String, Long>>()

    private val watch = TvStartWatch(
        later = { delay, run -> timers += delay to run },
        now = { clock },
        waitMs = 8_000,
    ) { id, token, waited -> stuck += Triple(id, token, waited) }

    private fun settle() {
        clock += 8_000
        timers.toList().also { timers.clear() }.forEach { it.second() }
    }

    @Test
    fun `a TV that fetched the picture and asked for no sound in eight seconds is stuck`() {
        watch.pictureFetched("yt", "tok")

        assertEquals(8_000L, timers.single().first)
        settle()

        assertEquals(listOf(Triple("yt", "tok", 8_000L)), stuck)
    }

    @Test
    fun `sound asked for in time means it started`() {
        watch.pictureFetched("yt", "tok")
        watch.soundFetched("tok")

        settle()

        assertTrue(stuck.isEmpty())
        assertTrue(watch.started("tok"))
    }

    @Test
    fun `sound asked for before the picture is never watched for`() {
        watch.soundFetched("tok")

        watch.pictureFetched("yt", "tok")

        assertTrue(timers.isEmpty())
    }

    @Test
    fun `many picture requests arm one watch`() {
        repeat(5) { watch.pictureFetched("yt", "tok") }

        assertEquals(1, timers.size)
    }

    @Test
    fun `a session that is forgotten is not judged`() {
        watch.pictureFetched("yt", "tok")
        watch.forget("tok")

        settle()

        assertTrue(stuck.isEmpty())
        assertFalse(watch.started("tok"))
    }

    @Test
    fun `each token is watched on its own`() {
        watch.pictureFetched("yt", "one")
        watch.pictureFetched("yt", "two")
        watch.soundFetched("two")

        settle()

        assertEquals(listOf("one"), stuck.map { it.second })
    }
}
