package com.dewijones92.totum.playback.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenCaptionsTest {

    @Test
    fun thePhonesCaptionLanguageGoesToTheScreen() {
        assertEquals("en", captionsWanted(listOf("en"), textOff = false))
        assertEquals("""{"do":"subs","lang":"en"}""", subsCommand(captionsWanted(listOf("en"), textOff = false)))
    }

    @Test
    fun captionsSwitchedOffOnThePhoneAreOffOnTheScreen() {
        assertNull(captionsWanted(listOf("en"), textOff = true))
        assertEquals("""{"do":"subs"}""", subsCommand(captionsWanted(listOf("en"), textOff = true)))
    }

    @Test
    fun noLanguageChosenMeansNoCaptions() {
        assertNull(captionsWanted(emptyList(), textOff = false))
    }
}
