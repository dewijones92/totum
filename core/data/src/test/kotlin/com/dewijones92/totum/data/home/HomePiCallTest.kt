package com.dewijones92.totum.data.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePiCallTest {

    @Test
    fun `a plain reason gets a cross in front`() {
        assertEquals("❌ unreadable request", HomePiCall.emojiLed("unreadable request"))
        assertEquals("❌ 404 from Jellyfin", HomePiCall.emojiLed("404 from Jellyfin"))
        assertEquals("❌ Épisode introuvable", HomePiCall.emojiLed("Épisode introuvable"))
    }

    @Test
    fun `a reason that already starts with an emoji is left alone, even one Unicode files as a letter`() {
        assertEquals("❌ Not enough space", HomePiCall.emojiLed("❌ Not enough space"))
        assertEquals("⚠️ Slow", HomePiCall.emojiLed("⚠️ Slow"))
        assertEquals("ℹ️ Jellyfin isn't set up", HomePiCall.emojiLed("ℹ️ Jellyfin isn't set up"))
        assertEquals("🚫 Refused", HomePiCall.emojiLed("🚫 Refused"))
    }

    @Test
    fun `surrounding space is trimmed either way`() {
        assertEquals("❌ spaced", HomePiCall.emojiLed("  spaced \n"))
        assertEquals("⚠️ spaced", HomePiCall.emojiLed("  ⚠️ spaced "))
    }

    @Test
    fun `an unknown route means the home server is out of date`() {
        assertEquals(HomePiCall.NEEDS_UPDATING, HomePiCall.reasonFor(404, "no such route: GET /jellyfin/browse"))
        assertEquals(HomePiCall.NEEDS_UPDATING, HomePiCall.reasonFor(404, "No such route"))
        assertEquals("⚠️ The home server needs updating for this", HomePiCall.NEEDS_UPDATING)
    }

    @Test
    fun `other reasons keep their own words`() {
        assertEquals("❌ Jellyfin said no", HomePiCall.reasonFor(502, "Jellyfin said no"))
        assertEquals("❌ The home server said no (HTTP 500)", HomePiCall.reasonFor(500, null))
    }
}
