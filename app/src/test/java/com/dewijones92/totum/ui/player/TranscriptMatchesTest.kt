package com.dewijones92.totum.ui.player

import com.dewijones92.totum.data.transcript.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMatchesTest {

    private val cues = listOf(
        Cue(0, 1_000, "Brighton were sensational on Saturday"),
        Cue(1_000, 2_000, "and Chelsea collapsed again"),
        Cue(2_000, 3_000, "the Brighton manager was delighted"),
    )

    @Test
    fun `a typo still finds the lines, like every other list`() {
        assertEquals(listOf(0, 2), matchesOf(cues, "brigton"))
    }

    @Test
    fun `every word has to be there, in any order`() {
        assertEquals(listOf(2), matchesOf(cues, "manager brighton"))
    }

    @Test
    fun `a blank query finds nothing`() {
        assertEquals(emptyList<Int>(), matchesOf(cues, "   "))
    }
}
