package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DripWatchTest {

    private var clock = 0L
    private val speeds = RangeSpeeds()
    private val watch = DripWatch(speeds, now = { clock })

    @Test
    fun `the range from the CI failure is a drip three seconds in`() {
        watch.rangeOpened(fresh = true)
        watch.reading { clock = 3_000 }
        watch.served(39 * 1024)

        val drip = watch.drip()

        assertNotNull(drip)
        assertEquals(1, drip?.reask)
        assertEquals(DripWatch.FLOOR, drip?.threshold)
    }

    @Test
    fun `time the player spends not reading is not dripping`() {
        watch.rangeOpened(fresh = true)
        watch.reading { clock += 100 }
        watch.served(1024 * 1024)
        clock += 60_000
        watch.reading { clock += 5 }
        watch.served(16 * 1024)

        assertNull("a full buffer pauses the loader mid-range; that pause is not the server", watch.drip())
    }

    @Test
    fun `nothing is judged inside the grace period however slow`() {
        watch.rangeOpened(fresh = true)
        watch.reading { clock = 2_999 }
        watch.served(1)

        assertNull(watch.drip())
    }

    @Test
    fun `a range keeping pace is left alone`() {
        watch.rangeOpened(fresh = true)
        watch.reading { clock = 3_000 }
        watch.served(3 * 1024 * 1024)

        assertNull(watch.drip())
    }

    @Test
    fun `a slow network is judged against its own typical speed, not a fast one`() {
        repeat(5) {
            watch.rangeOpened(fresh = true)
            watch.reading { clock += 10_000 }
            watch.served(200 * 1024)
            watch.rangeFinished()
        }
        watch.rangeOpened(fresh = true)
        watch.reading { clock += 4_000 }
        watch.served(70 * 1024)

        assertEquals(DripWatch.KNOWN_FLOOR, watch.threshold())
        assertNull("17KB/s on a 20KB/s network is that network, not a drip", watch.drip())
    }

    @Test
    fun `on a fast network a range far below its usual speed is a drip`() {
        repeat(5) {
            watch.rangeOpened(fresh = true)
            watch.reading { clock += 1_000 }
            watch.served(4 * 1024 * 1024)
            watch.rangeFinished()
        }
        watch.rangeOpened(fresh = true)
        watch.reading { clock += 3_000 }
        watch.served(300 * 1024)

        assertEquals(DripWatch.CEILING, watch.threshold())
        assertNotNull(watch.drip())
    }

    @Test
    fun `a range is re-asked at most twice, and a fresh range starts the count again`() {
        watch.rangeOpened(fresh = true)
        repeat(3) {
            watch.reading { clock += 3_000 }
            watch.served(1024)
            watch.drip()
            watch.rangeOpened(fresh = false)
        }
        assertEquals(2, watch.reasks)
        watch.reading { clock += 3_000 }
        assertNull(watch.drip())

        watch.rangeOpened(fresh = true)
        watch.reading { clock += 3_000 }
        watch.served(1024)
        assertNotNull(watch.drip())
    }

    @Test
    fun `a range too short to measure leaves the typical speed alone`() {
        watch.rangeOpened(fresh = true)
        watch.reading { clock += 50 }
        watch.served(1024)

        assertNull(watch.rangeFinished())
        assertNull(speeds.typical())
    }

    @Test
    fun `the typical speed is the median of the last nine ranges`() {
        listOf(1L, 2, 3, 4, 100, 200, 300, 400, 500, 600, 700).forEach(speeds::record)

        assertEquals(300L, speeds.typical())
    }
}
