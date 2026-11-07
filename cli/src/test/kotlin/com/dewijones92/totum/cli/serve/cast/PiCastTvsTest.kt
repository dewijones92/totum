package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.playback.CastProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PiCastTvsTest {

    private val tvs = PiCastTvs()
    private val lg = PiCastTv("LG TV") { "OLED55C14LA" }
    private val ultra = PiCastTv("Chromecast Ultra") { "Chromecast Ultra" }

    @Test
    fun `each item is shown the screen of the TV it was sent to`() {
        tvs.expect("LG TV|a", lg)
        tvs.expect("Chromecast Ultra|b", ultra)

        assertEquals("LG TV", tvs.current("LG TV|a")!!.name)
        assertEquals(4096.coerceAtMost(2160), tvs.current("LG TV|a")!!.screen.maxHeight)
        assertEquals(1080, tvs.current("Chromecast Ultra|b")!!.screen.maxHeight)
        assertNull(tvs.current("someone else|c"))
    }

    @Test
    fun `a video the TV cannot show is a notice that names it and the TV, on that TV only`() {
        tvs.expect("LG TV|a", lg)
        tvs.expect("Chromecast Ultra|b", ultra)

        tvs.videos(mapOf("LG TV|a" to "4K AV1"), emptyMap(), emptyMap(), emptyMap())

        assertTrue(lg.notice, lg.notice!!.contains("sound only") || lg.notice!!.contains("sound"))
        assertTrue(lg.notice, lg.notice!!.contains("4K AV1"))
        assertNull(ultra.notice)
    }

    @Test
    fun `a picture that is sent clears the notice a refusal left`() {
        tvs.expect("LG TV|a", lg)
        tvs.videos(mapOf("LG TV|a" to "4K AV1"), emptyMap(), emptyMap(), emptyMap())

        tvs.videos(emptyMap(), mapOf("LG TV|a" to QualityLabel.of(1080, "avc1")), emptyMap(), emptyMap())

        assertNull(lg.notice)
    }

    @Test
    fun `a problem goes to the TV that was last sent something, and null clears it`() {
        tvs.expect("LG TV|a", lg)

        tvs.problem(CastProblem.TV_NOT_FETCHING)
        assertTrue(lg.notice, lg.notice!!.contains("not fetching"))

        tvs.problem(null)
        assertNull(lg.notice)
    }
}
