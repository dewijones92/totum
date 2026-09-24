package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatchTest {

    private fun hit(query: String, vararg fields: String?) = FuzzyMatch.matches(query, fields.toList())

    @Test
    fun `an empty query matches everything`() {
        assertTrue(hit("", "anything"))
        assertTrue(hit("   ", "anything"))
    }

    @Test
    fun `case, accents and punctuation do not matter`() {
        assertTrue(hit("geronimo", "TCV: Tuchel talks Trent, Clinton clasps & Gerónimo!"))
        assertTrue(hit("AC DC", "AC/DC Power Up Tour"))
        assertTrue(hit("acdc", "AC/DC"))
    }

    @Test
    fun `every word must be found, in any order and in any field`() {
        assertTrue(hit("daily brighton", "MNC: Brentford, Brighton & the silent treatment", "Football Daily"))
        assertFalse(hit("daily chelsea", "MNC: Brentford, Brighton & the silent treatment", "Football Daily"))
    }

    @Test
    fun `a typo is forgiven in a word long enough to have one`() {
        assertTrue(hit("brigton", "Brighton Sensational"))
        assertTrue(hit("footbal", "Football Daily"))
        assertTrue(hit("computrephile", "Computerphile"))
        assertFalse(hit("cat", "car crash"))
    }

    @Test
    fun `a word can be abbreviated by its letters in order`() {
        assertTrue(hit("ftbl", "Football Daily"))
        assertTrue(hit("cmptr", "Computerphile"))
        assertFalse(hit("bfl", "Football Daily"))
    }

    @Test
    fun `a partial word matches as you type`() {
        assertTrue(hit("brig", "Brighton"))
        assertTrue(hit("ighto", "Brighton"))
    }

    @Test
    fun `a one or two letter query must start a word rather than hide inside one`() {
        assertTrue(hit("ai", "OpenAI GPT-6 Astra: Major Reduction in AI Hallucinations"))
        assertTrue(hit("ai", "Aim higher"))
        assertFalse(hit("ai", "DAY DRINKER Official Trailer (2027) Johnny Depp", "ONE Media"))
        assertFalse(hit("ai", "Pure entertainment out there"))
        assertTrue(hit("ac", "AC/DC Power Up Tour"))
    }

    @Test
    fun `a short word is not a typo of a different short word`() {
        assertFalse(hit("live", "I like this"))
        assertFalse(hit("live", "Love actually"))
        assertFalse(hit("news", "What's new"))
        assertFalse(hit("game", "Same again"))
        assertFalse(hit("chess", "Cheese board"))
        assertFalse(hit("tenn", "Teenage kicks"))
    }

    @Test
    fun `numbers are matched exactly, never as typos`() {
        assertFalse(hit("2024", "Best of 2025"))
        assertFalse(hit("1234", "Episode 1239"))
        assertFalse(hit("1234", "Episode 234"))
        assertTrue(hit("2025", "Best of 2025"))
        assertTrue(hit("episode 12", "Episode 123"))
    }

    @Test
    fun `an extra letter mid-word still matches while typing`() {
        assertTrue(hit("briig", "Brighton"))
        assertTrue(hit("briigh", "Brighton"))
        assertTrue(hit("briight", "Brighton"))
    }

    @Test
    fun `three letters is not an abbreviation and joined words must start at a word`() {
        assertFalse(hit("cat", "Create something"))
        assertFalse(hit("cat", "Live chat"))
        assertFalse(hit("heart", "The Art of War"))
        assertFalse(hit("note", "Piano teacher"))
        assertTrue(hit("theart", "The Art of War"))
    }

    @Test
    fun `words in scripts without spaces match anywhere`() {
        assertTrue(hit("京都", "今日は京都へ行く"))
        assertTrue(hit("東京", "東京タワー"))
    }

    @Test
    fun `a query of only symbols filters nothing`() {
        assertTrue(hit("#", "anything"))
        assertEquals(false, FuzzyMatch.hasTerms("# - !"))
        assertEquals(true, FuzzyMatch.hasTerms("a"))
    }

    @Test
    fun `sharp s reads as ss`() {
        assertTrue(hit("strasse", "Straße"))
    }

    @Test
    fun `a word with vowels is never read as an abbreviation`() {
        assertFalse(hit("live", "leokimvideo"))
        assertTrue(hit("brgtn", "Brighton"))
    }

    @Test
    fun `unrelated text does not match`() {
        assertFalse(hit("tennis", "Football Daily", "BBC Radio 5 Live"))
        assertFalse(hit("xyzzy", "Anything at all"))
    }

    @Test
    fun `filtering keeps the list's own order`() {
        val list = listOf("zebra football", "alpha", "football club")

        assertEquals(listOf("zebra football", "football club"), list.fuzzyFiltered("football") { listOf(it) })
        assertEquals(list, list.fuzzyFiltered("") { listOf(it) })
    }
}
