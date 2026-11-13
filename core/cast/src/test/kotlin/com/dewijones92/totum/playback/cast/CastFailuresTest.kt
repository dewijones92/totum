package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CastFailuresTest {

    private var clock = 1_000_000L
    private val tv = FakeCastTv()

    private fun failures() = CastFailures(tv, { clock })

    @Test
    fun `one or two failures say nothing to the user, the third does`() {
        val f = failures()

        assertFalse(f.failed("a", "manifest.mpd", IOException("boom")))
        assertFalse(f.failed("a", "manifest.mpd", IOException("boom")))
        assertTrue(f.failed("a", "manifest.mpd", IOException("boom")))

        assertEquals(listOf<Pair<String, String?>>("a" to "boom"), tv.failures)
    }

    @Test
    fun `it is told once, however many more failures follow`() {
        val f = failures()

        repeat(6) { f.failed("a", "audio/0.m4s", IOException("boom")) }

        assertEquals(1, tv.failures.size)
    }

    @Test
    fun `failures that are a minute apart are not a pattern`() {
        val f = failures()

        repeat(5) {
            f.failed("a", "manifest.mpd", IOException("boom"))
            clock += 61_000
        }

        assertTrue(tv.failures.isEmpty())
    }

    @Test
    fun `a file with no readable tracks is told as its track list being at the end`() {
        val f = failures()

        repeat(3) { f.failed("a", "manifest.mpd", NoReadableTracks("a", "0 track(s): none; seekability unknown")) }

        assertEquals(CastFailures.TRACK_LIST_AT_THE_END, tv.failures.single().second)
    }

    @Test
    fun `the reason is found under a wrapping exception too`() {
        val wrapped = IOException("could not prepare", NoReadableTracks("a", "0 track(s)"))

        assertEquals(CastFailures.TRACK_LIST_AT_THE_END, CastFailures.reasonOf(wrapped))
    }

    @Test
    fun `when it works after all the notice is cleared`() {
        val f = failures()
        repeat(3) { f.failed("a", "manifest.mpd", IOException("boom")) }

        f.worked("a")

        assertEquals(listOf<Pair<String, String?>>("a" to "boom", "a" to null), tv.failures)
    }

    @Test
    fun `working with nothing told says nothing`() {
        failures().worked("a")

        assertTrue(tv.failures.isEmpty())
    }
}
