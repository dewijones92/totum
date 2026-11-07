package com.dewijones92.totum.data.spoiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoilerTitlesTest {

    private val titles = listOf(
        "Rinky Hijikata vs Novak Djokovic Highlights | Paris 2026 Round 2",
        "Qualifier Rinky Hijikata Takes Down Novak Djokovic",
        "Alcaraz beats Sinner in five sets to lift the title",
        "Sinner Defeats Zverev 6-4 6-3 | Highlights",
        "Epic comeback: Medvedev saves match points to edge Rublev",
        "Swiatek v Sabalenka | Final Highlights | Madrid 2026",
        "Gauff stuns Rybakina in three sets",
        "Djokovic wins his 25th Grand Slam",
        "Full Match | Nadal vs Ruud | Roland Garros 2022 Final",
        "Walkover: Fritz withdraws before the semi-final",
        "Medvedev def. Tsitsipas 7-6(4) 6-4",
        "Thriller in Turin: Ruud survives Fritz",
        "Zverev through to the final after a marathon",
        "Press conference | Carlos Alcaraz | Wimbledon 2026",
        "Practice session with Jannik Sinner",
        "Draw ceremony | US Open 2026",
        "Match point | Rafael Nadal | Barcelona",
        "Sabalenka crushes Pegula 6-0 6-1",
        "Tsitsipas knocked out by qualifier in round one",
        "Raducanu retires injured against Kasatkina",
        "Brutal battle: Rune dominated Draper in straights",
        "Highlights: Fritz v Paul | Cincinnati 2026",
        "Jabeur upsets Kvitova to reach the quarterfinals",
        "Rublev thrashes de Minaur 6-2 6-2",
        "Behind the scenes at the ATP Finals",
    )

    private val proposals: List<(String) -> String?> = listOf(
        { null },
        { "" },
        { it },
        { "$it wins" },
        { it.replace(" beats ", " v ") },
        { it.take(20) },
        { "Highlights | Paris" },
        { "Winner announced" },
        { "A great match" },
    )

    @Test
    fun `whatever the model says, a title that is shown never names a result`() {
        titles.forEach { original ->
            proposals.forEach { propose ->
                listOf(true, false, null).forEach { spoils ->
                    val decided = SpoilerRules.decide(original, spoils, propose(original))
                    assertFalse(
                        "\"$original\" (model said $spoils, offered ${propose(original)}) became \"${decided.title}\"",
                        SpoilerRules.reveals(decided.title),
                    )
                }
            }
        }
    }

    @Test
    fun `a title with no result in it that the model also clears is shown exactly as written`() {
        val plain = titles.filterNot(SpoilerRules::reveals)

        assertTrue(plain.isNotEmpty())
        plain.forEach { original ->
            val decided = SpoilerRules.decide(original, false, null)
            assertEquals(original, decided.title)
            assertFalse(decided.spoils)
        }
    }

    @Test
    fun `titles that state a result are caught by a rule, not left to the model`() {
        val stating = listOf(
            "Alcaraz beats Sinner in five sets to lift the title",
            "Sinner Defeats Zverev 6-4 6-3 | Highlights",
            "Gauff stuns Rybakina in three sets",
            "Djokovic wins his 25th Grand Slam",
            "Medvedev def. Tsitsipas 7-6(4) 6-4",
            "Raducanu retires injured against Kasatkina",
            "Tsitsipas knocked out by qualifier in round one",
        )

        stating.forEach { original ->
            assertTrue("\"$original\" was not caught", SpoilerRules.reveals(original))
            val decided = SpoilerRules.decide(original, false, null)
            assertTrue("\"$original\" was kept", decided.spoils)
            assertEquals(SpoilerRules.NEUTRAL, decided.title)
        }
    }

    @Test
    fun `titles that name the players and nothing more are left for the model, which hides the order`() {
        val naming = listOf(
            "Swiatek v Sabalenka | Final Highlights | Madrid 2026",
            "Full Match | Nadal vs Ruud | Roland Garros 2022 Final",
            "Press conference | Carlos Alcaraz | Wimbledon 2026",
            "Draw ceremony | US Open 2026",
        )

        naming.forEach { assertFalse("\"$it\" is no result", SpoilerRules.reveals(it)) }
    }
}
