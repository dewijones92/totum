package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionPickTest {

    @Test
    fun `an exact language wins over a regional cousin, in the order the languages were wanted`() {
        assertEquals(1, CaptionPick.indexOf(listOf("en-gb", "en", "cy"), listOf("en")))
        assertEquals(2, CaptionPick.indexOf(listOf("en", "fr", "cy"), listOf("cy", "en")))
    }

    @Test
    fun `without an exact match the same language is taken, and a different one never is`() {
        assertEquals(0, CaptionPick.indexOf(listOf("en-gb", "cy"), listOf("en-us")))
        assertNull(CaptionPick.indexOf(listOf("en", "cy"), listOf("fr")))
        assertNull(CaptionPick.indexOf(emptyList(), listOf("en")))
    }

    @Test
    fun `codes are normalised the way the phone's selector does it, three letters to two and the region kept`() {
        assertEquals("en", CaptionPick.normal("en"))
        assertEquals("en", CaptionPick.normal("eng"))
        assertEquals("en-gb", CaptionPick.normal("en_GB"))
        assertEquals("cy", CaptionPick.normal("CY"))
        assertEquals("cy", CaptionPick.normal("cym"))
        assertEquals("", CaptionPick.normal("  "))
    }
}
