package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The shape YouTube uses, so a yt-dlp-sourced row (a raw number) and an InnerTube-sourced
 * one (already text) sit in the same list without one of them looking foreign.
 */
class ViewCountFormatTest {

    @Test
    fun `small counts are exact`() {
        assertEquals("0 views", formatViewCount(0))
        assertEquals("1 views", formatViewCount(1))
        assertEquals("999 views", formatViewCount(999))
    }

    @Test
    fun `thousands, millions and billions get their suffix`() {
        assertEquals("1K views", formatViewCount(1_000))
        assertEquals("1.2M views", formatViewCount(1_234_567))
        assertEquals("2.5B views", formatViewCount(2_500_000_000))
    }

    @Test
    fun `a whole number keeps no trailing decimal`() {
        assertEquals("12K views", formatViewCount(12_000))
        assertEquals("3M views", formatViewCount(3_000_000))
    }

    @Test
    fun `counts round down, never up`() {
        // 999,999 is not a million yet, and saying so would be a small lie repeated everywhere.
        assertEquals("999.9K views", formatViewCount(999_999))
    }
}
