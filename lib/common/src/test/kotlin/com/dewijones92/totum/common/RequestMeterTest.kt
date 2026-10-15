package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestMeterTest {
    private var clock = 0L
    private val meter = RequestMeter { clock }

    @Test
    fun `counts the last hour per host, with 429s and refusals`() {
        meter.record("www.youtube.com", 200)
        meter.record("www.youtube.com", 429)
        meter.record("www.youtube.com", 403)
        meter.record("itunes.apple.com", 200)
        clock += 30 * 60_000
        meter.record("www.youtube.com", 200)
        assertEquals(
            mapOf(
                "itunes.apple.com" to "1 call(s), 0 429, 0 other 4xx",
                "www.youtube.com" to "4 call(s), 1 429, 1 other 4xx"
            ),
            meter.lastHour(),
        )
        clock += 31 * 60_000
        assertEquals(mapOf("www.youtube.com" to "1 call(s), 0 429, 0 other 4xx"), meter.lastHour())
    }
}
