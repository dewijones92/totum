package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Vitals
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class RecentRoutesTest {

    @After
    fun clear() = Vitals.clear()

    @Test
    fun `a report hours later still says how each recent item started, newest last`() {
        var at = LocalTime.of(0, 44, 2)
        val routes = RecentRoutes(kept = 2, now = { at })

        routes.remember("route a -> streaming the video [copy=none]")
        at = LocalTime.of(0, 51, 0)
        routes.remember("route b -> the downloaded audio [copy=audio-only]")
        at = LocalTime.of(6, 50, 17)
        routes.remember("route b -> the downloaded audio [copy=audio-only listen=true]")

        assertEquals(
            "00:51:00 route b -> the downloaded audio [copy=audio-only] || " +
                "06:50:17 route b -> the downloaded audio [copy=audio-only listen=true]",
            Vitals.snapshot()[RecentRoutes.VITAL],
        )
    }
}
