package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CastFormatTest {

    private val lg = CastScreen.of("OLED65C24LA")

    @Test
    fun `DASH judges a screen as it is and carries any container`() {
        assertSame(lg, CastFormat.DASH.screenFor(lg))
        assertNull(CastFormat.DASH.screenFor(null))
        assertTrue(CastFormat.DASH.carries(Container.WEBM))
    }

    @Test
    fun `HLS drops VP9 from a screen that takes it, keeping the rest of what is known about it`() {
        val narrowed = CastFormat.HLS.screenFor(lg)!!

        assertEquals(setOf(VideoCodecFamily.HEVC, VideoCodecFamily.AVC), narrowed.codecs)
        assertEquals(1080, narrowed.maxHeightOf(VideoCodecFamily.AVC))
        assertEquals(2160, narrowed.maxHeightOf(VideoCodecFamily.HEVC))
        assertFalse(narrowed.canDecode("vp09.00.50.08", 2160))
        assertTrue(lg.canDecode("vp09.00.50.08", 2160))
    }

    @Test
    fun `HLS with no screen at all assumes H264 only, and no AV1 ever`() {
        val assumed = CastFormat.HLS.screenFor(null)!!

        assertEquals(setOf(VideoCodecFamily.AVC), assumed.codecs)
        assertFalse(assumed.known)
        assertFalse(CastFormat.HLS.screenFor(lg)!!.canDecode("av01.0.08M.08", 1080))
    }

    @Test
    fun `HLS carries MP4 and not WebM`() {
        assertTrue(CastFormat.HLS.carries(Container.MP4))
        assertFalse(CastFormat.HLS.carries(Container.WEBM))
    }

    @Test
    fun `the rendition playlists count as manifests with the two formats' own`() {
        assertTrue(CastFormat.isManifest("hls.m3u8"))
        assertTrue(CastFormat.isManifest("hls-video.m3u8"))
        assertTrue(CastFormat.isManifest("hls-audio.m3u8"))
        assertTrue(CastFormat.isManifest("manifest.mpd"))
        assertFalse(CastFormat.isManifest("video/0.m4s"))
    }

    @Test
    fun `sound only keeps the item but drops the picture it could offer`() {
        val item = CastItem("a", "https://x/y.mp4", ladder = listOf(CastRung("https://x/r", 720, "avc1")))

        val sound = item.soundOnly()

        assertTrue(sound.listening)
        assertTrue(sound.ladder.isEmpty())
        assertEquals(item.uri, sound.uri)
    }
}
