package com.dewijones92.totum.jobs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.LocalDateTime

class MorningWorkerTest {

    @Test
    fun `before half past seven the first run is this morning`() {
        val now = LocalDateTime.of(2026, 11, 7, 6, 0)
        assertEquals(Duration.ofMinutes(90), MorningWorker.untilMorning(now))
    }

    @Test
    fun `after half past seven the first run is tomorrow morning`() {
        val now = LocalDateTime.of(2026, 11, 7, 8, 30)
        assertEquals(Duration.ofHours(23), MorningWorker.untilMorning(now))
    }

    @Test
    fun `exactly half past seven waits a whole day, not zero`() {
        val now = LocalDateTime.of(2026, 11, 7, 7, 30)
        assertEquals(Duration.ofDays(1), MorningWorker.untilMorning(now))
    }
}
