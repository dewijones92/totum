package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecRefusalsTest {

    private class Store(var saved: Map<String, Set<VideoCodecFamily>> = emptyMap()) : RefusalStore {
        var saves = 0
        override fun load() = saved
        override fun save(refusals: Map<String, Set<VideoCodecFamily>>) {
            saved = refusals
            saves++
        }
    }

    private val screen = CastScreen.of("Chromecast")

    @Test
    fun `a codec that was only tried is set aside for this cast and not saved`() {
        val store = Store()
        val refusals = CodecRefusals(store)

        refusals.tried("Chromecast", VideoCodecFamily.AVC)

        assertEquals(setOf(VideoCodecFamily.AVC), refusals.refusedFor("chromecast"))
        assertEquals(0, store.saves)
    }

    @Test
    fun `it is saved once another codec played, and a later run reads it back`() {
        val store = Store()
        val refusals = CodecRefusals(store)
        refusals.tried("Chromecast", VideoCodecFamily.VP9)

        refusals.worked("Chromecast", VideoCodecFamily.VP9)

        assertEquals(mapOf("chromecast" to setOf(VideoCodecFamily.VP9)), store.saved)
        assertEquals(setOf(VideoCodecFamily.VP9), CodecRefusals(store).refusedFor("Chromecast"))
    }

    @Test
    fun `a codec that was never tried is not saved by something else playing`() {
        val store = Store()
        val refusals = CodecRefusals(store)

        refusals.worked("Chromecast", VideoCodecFamily.VP9)

        assertEquals(0, store.saves)
        assertTrue(refusals.refusedFor("Chromecast").isEmpty())
    }

    @Test
    fun `ending the cast drops what was only tried, keeps what was confirmed`() {
        val refusals = CodecRefusals(Store(mapOf("chromecast" to setOf(VideoCodecFamily.VP9))))
        refusals.tried("Chromecast", VideoCodecFamily.AVC)

        refusals.forgetTentative("Chromecast")

        assertEquals(setOf(VideoCodecFamily.VP9), refusals.refusedFor("Chromecast"))
    }

    @Test
    fun `a screen loses what was refused for its model`() {
        val refusals = CodecRefusals()
        refusals.tried("Chromecast", VideoCodecFamily.VP9)

        val applied = refusals.appliedTo(screen)

        assertFalse(applied.canDecode("vp09.00.40.08", 720))
        assertTrue(applied.canDecode("avc1.64001e", 720))
        assertTrue(CodecRefusals().appliedTo(screen).canDecode("vp09.00.40.08", 720))
    }

    @Test
    fun `another model is not affected`() {
        val refusals = CodecRefusals()
        refusals.tried("Chromecast", VideoCodecFamily.VP9)

        assertTrue(refusals.refusedFor("Chromecast Ultra").isEmpty())
    }

    @Test
    fun `a codec is only worth refusing while something else is left`() {
        val refusals = CodecRefusals()

        assertTrue(refusals.leavesSomethingAfter(screen, VideoCodecFamily.VP9))
        refusals.tried("Chromecast", VideoCodecFamily.VP9)
        assertFalse(refusals.leavesSomethingAfter(screen, VideoCodecFamily.AVC))
    }
}
