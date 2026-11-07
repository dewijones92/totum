package com.dewijones92.totum.data.spoiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerRulesTest {

    @Test
    fun `result words, hype words and scores are spotted`() {
        listOf(
            "Alcaraz beats Sinner in Paris",
            "Qualifier Rinky Hijikata Takes Down Novak Djokovic and wins",
            "Sinner d. Alcaraz | Final",
            "Alcaraz def. Sinner",
            "Alcaraz v Sinner 6-4 6-3",
            "Sinner v Zverev 7-6(5)",
            "A five-set BATTLE!",
            "Won in straight sets",
            "MARATHON Classic!",
            "Zverev shares a laugh after Final defeat",
            "Muchova saves THREE championship points",
            "Sinner reacts to retaining his Championship",
        ).forEach { assertTrue("should reveal: $it", SpoilerRules.reveals(it)) }
    }

    @Test
    fun `plain titles, initials and dates are not taken for results`() {
        listOf(
            "Carlos Alcaraz vs Taylor Fritz Highlights | Paris 2026 Quarter-Finals",
            "D. Medvedev v A. Zverev | Round 2",
            "Premier League 2013-14 review",
            "Extreme Conditions Ep 3 | Speed & Control | ATP X Yokohama",
            "Winners That Are Out of This World",
            "Final press conference | Wimbledon 2026",
            "Draw ceremony 12:30-13:30",
        ).forEach { assertFalse("should not reveal: $it", SpoilerRules.reveals(it)) }
    }

    @Test
    fun `a title with nothing in it that the model also clears is kept as written`() {
        val decided = SpoilerRules.decide("Alcaraz Road To Turin", spoils = false, proposed = "Alcaraz Road To Turin")
        assertEquals("Alcaraz Road To Turin", decided.title)
        assertFalse(decided.spoils)
    }

    @Test
    fun `a rewrite is used when the title spoils and the rewrite is clean`() {
        val decided = SpoilerRules.decide(
            "Carlos Alcaraz GOD MODE COMEBACK vs Fritz | Paris 2026",
            spoils = true,
            proposed = "Carlos Alcaraz v Fritz | Paris 2026",
        )
        assertEquals("Carlos Alcaraz v Fritz | Paris 2026", decided.title)
        assertTrue(decided.spoils)
    }

    @Test
    fun `the model saying a title is fine does not beat a rule that sees a result`() {
        val title = "MARATHON Classic! | Djokovic vs Auger-Aliassime"
        val decided = SpoilerRules.decide(title, false, "Djokovic v Auger-Aliassime")
        assertEquals("Djokovic v Auger-Aliassime", decided.title)
        assertTrue(decided.spoils)
    }

    @Test
    fun `a rewrite that still names a result is replaced by the neutral title`() {
        val decided = SpoilerRules.decide("Alcaraz beats Sinner", true, "Alcaraz wins vs Sinner")
        assertEquals(SpoilerRules.NEUTRAL, decided.title)
    }

    @Test
    fun `no rewrite for a spoiling title gives the neutral title`() {
        assertEquals(SpoilerRules.NEUTRAL, SpoilerRules.decide("Alcaraz beats Sinner", true, "").title)
        assertEquals(SpoilerRules.NEUTRAL, SpoilerRules.decide("Alcaraz beats Sinner", null, null).title)
    }

    @Test
    fun `a rewrite that shares no word with the title is invented and is dropped`() {
        val decided = SpoilerRules.decide("Alcaraz beats Sinner", true, "Federer v Nadal")
        assertEquals(SpoilerRules.NEUTRAL, decided.title)
    }

    @Test
    fun `a rewrite much longer than the title is dropped`() {
        val longer = "Alcaraz v Sinner in an exciting match at the Paris Masters"
        val decided = SpoilerRules.decide("Alcaraz beats Sinner", true, longer)
        assertEquals(SpoilerRules.NEUTRAL, decided.title)
    }

    @Test
    fun `the model saying spoils with a clean title and no result words still uses the rewrite`() {
        val decided = SpoilerRules.decide("Sinner vs Alcaraz Highlights", true, "Alcaraz v Sinner Highlights")
        assertEquals("Alcaraz v Sinner Highlights", decided.title)
    }
}
