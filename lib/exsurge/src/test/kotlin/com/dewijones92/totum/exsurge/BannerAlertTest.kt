package com.dewijones92.totum.exsurge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class BannerAlertTest {
    private val now = Instant.parse("2026-10-15T09:00:00Z")
    private fun sitting(
        secondsLeft: Long
    ) = BannerLine.Sitting(now.plusSeconds(secondsLeft), satMinutes = 29, limitMinutes = 30)

    @Test
    fun `the summons a minute away pops once`() {
        assertEquals("summons in a minute", bannerAlert(sitting(120), sitting(50), now))
        assertNull(bannerAlert(sitting(50), sitting(20), now))
    }

    @Test
    fun `a break starting and ending pop`() {
        val onBreak = BannerLine.OnBreak(now.plusSeconds(300), steps = 10, lengthMinutes = 10)
        assertEquals("break started", bannerAlert(BannerLine.Rising(30, 40), onBreak, now))
        assertEquals("break over", bannerAlert(onBreak, sitting(1800), now))
    }

    @Test
    fun `ordinary updates stay silent`() {
        assertNull(bannerAlert(sitting(1800), sitting(1740), now))
        assertNull(bannerAlert(null, sitting(1800), now))
        assertNull(bannerAlert(BannerLine.Off, BannerLine.Sleeping(null), now))
        assertNull(bannerAlert(sitting(1800), BannerLine.Sitting(null, 0, 30), now))
    }
}
