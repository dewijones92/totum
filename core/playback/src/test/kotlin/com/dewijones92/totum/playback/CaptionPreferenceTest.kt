package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionPreferenceTest {

    @Test
    fun `captions are on in English for anything, until told otherwise`() {
        val captions = CaptionPreference()

        assertEquals("en", captions.languageFor("a"))
        assertEquals("en", captions.languageFor("b"))
    }

    @Test
    fun `switching captions off lasts only for the item it was switched off on`() {
        val captions = CaptionPreference()

        captions.choose("a", null)

        assertNull(captions.languageFor("a"))
        assertEquals("the next item has them back", "en", captions.languageFor("b"))
    }

    @Test
    fun `a chosen language carries on to the next item`() {
        val captions = CaptionPreference()

        captions.choose("a", null)
        captions.choose("a", "fr")

        assertEquals("fr", captions.languageFor("a"))
        assertEquals("fr", captions.languageFor("b"))
    }
}
