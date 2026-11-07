package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.domain.VideoCodecFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastStandInTest {

    @Test
    fun `a codecs model lists the families by name or by codec string and caps at 1080p`() {
        val screen = standInScreen("codecs:vp9+avc1.640028+HEVC")

        assertEquals(setOf(VideoCodecFamily.VP9, VideoCodecFamily.AVC, VideoCodecFamily.HEVC), screen.codecs)
        assertEquals(1080, screen.maxHeight)
        assertFalse(screen.known)
    }

    @Test
    fun `a token that names no family is ignored`() {
        assertEquals(setOf(VideoCodecFamily.AV1), standInScreen("codecs:av01+nonsense").codecs)
    }

    @Test
    fun `any other model is read like a real device name`() {
        val screen = standInScreen("OLED55C1")

        assertTrue(screen.known)
        assertEquals(2160, screen.maxHeight)
    }
}
