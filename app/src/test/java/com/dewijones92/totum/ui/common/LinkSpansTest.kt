package com.dewijones92.totum.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkSpansTest {

    private fun shown(text: String) = linkSpans(text).map {
        when (it) {
            is LinkSpan.Web -> "web ${it.url}"
            is LinkSpan.Seek -> "seek ${it.positionMs}"
        }
    }

    @Test
    fun `a bare web address becomes a link, without the full stop that ends the sentence`() {
        assertEquals(listOf("web https://example.com/a?b=1"), shown("Notes at https://example.com/a?b=1."))
    }

    @Test
    fun `an address in brackets loses the closing bracket`() {
        assertEquals(listOf("web http://bbc.co.uk/x"), shown("(see http://bbc.co.uk/x)"))
    }

    @Test
    fun `a timestamp inside an address is part of the address, not a seek`() {
        assertEquals(listOf("web https://example.com/at/1:30"), shown("https://example.com/at/1:30"))
    }

    @Test
    fun `links and timestamps come back in reading order`() {
        assertEquals(
            listOf("seek 0", "web https://www.google.com/device", "seek 90000"),
            shown("0:00 intro, sign in at https://www.google.com/device, then 1:30"),
        )
    }

    @Test
    fun `text with neither has no spans`() {
        assertEquals(emptyList<String>(), shown("nothing to see here, 12 to 3"))
    }
}
