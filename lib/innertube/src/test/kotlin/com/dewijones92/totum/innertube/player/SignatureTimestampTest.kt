package com.dewijones92.totum.innertube.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureTimestampTest {

    @Test
    fun `the TV scale is the web number with a 001 suffix, as SmartTube sends it`() {
        assertEquals(20697001, SignatureTimestamp(20697).tv)
        assertEquals(20697, SignatureTimestamp(20697).web)
    }

    @Test
    fun `it prints both scales, so a log line can be re-judged after the fact`() {
        assertEquals("20697 (tv 20697001)", SignatureTimestamp(20697).toString())
    }
}
