package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptionPreferenceTest {

    @Test
    fun `captions are on in English for anything, until told otherwise`() {
        val captions = CaptionPreference(InMemoryCaptionStore())

        assertEquals("en", captions.languageFor())
    }

    @Test
    fun `switching captions off stays off for every item until they are turned on`() {
        val captions = CaptionPreference(InMemoryCaptionStore())

        captions.choose(null)

        assertNull(captions.languageFor())
        assertNull("the next item has them off too", captions.languageFor())
    }

    @Test
    fun `off survives a restart, because it is read back from the store`() {
        val store = InMemoryCaptionStore()
        CaptionPreference(store).choose(null)

        assertNull(CaptionPreference(store).languageFor())
    }

    @Test
    fun `turning them on again brings back the last language`() {
        val captions = CaptionPreference(InMemoryCaptionStore())

        captions.choose("fr")
        captions.choose(null)
        captions.choose("fr")

        assertEquals("fr", captions.languageFor())
    }

    @Test
    fun `a chosen language carries on to the next item`() {
        val captions = CaptionPreference(InMemoryCaptionStore())

        captions.choose(null)
        captions.choose("fr")

        assertEquals("fr", captions.languageFor())
    }
}
