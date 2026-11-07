package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SponsorLinkTest {

    private val page = "https://sponsor.example.org/someone"

    @Test
    fun `an empty or missing value hides the button`() {
        assertNull(SponsorLink.of(null))
        assertNull(SponsorLink.of(""))
        assertNull(SponsorLink.of("   "))
    }

    @Test
    fun `an https link is used as given`() {
        assertEquals(page, SponsorLink.of(page)?.value)
        assertEquals(page, SponsorLink.of("  $page ")?.value)
    }

    @Test
    fun `an at sign in the path is fine`() {
        assertEquals("$page/@me", SponsorLink.of("$page/@me")?.value)
    }

    @Test
    fun `anything that is not a plain https link hides the button`() {
        assertNull(SponsorLink.of("http://sponsor.example.org/someone"))
        assertNull(SponsorLink.of("sponsor.example.org/someone"))
        assertNull(SponsorLink.of("javascript:alert(1)"))
        assertNull(SponsorLink.of("intent://sponsor#Intent;scheme=https;end"))
        assertNull(SponsorLink.of("https://"))
        assertNull(SponsorLink.of("https://someone@sponsor.example.org/pay"))
        assertNull(SponsorLink.of("not a link at all"))
    }
}
