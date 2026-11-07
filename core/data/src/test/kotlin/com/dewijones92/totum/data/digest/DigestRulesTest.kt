package com.dewijones92.totum.data.digest

import com.dewijones92.totum.data.transcript.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestRulesTest {

    private fun cue(sec: Int, text: String) = Cue(sec * 1000L, sec * 1000L + 4000, text)

    private val cues = listOf(
        cue(0, "welcome to the show today we talk about sleep"),
        cue(5, "and why a cold bedroom helps you fall asleep faster"),
        cue(25, "the second thing is light in the morning"),
        cue(70, "so the main takeaway is that the temperature of the bedroom should be around eighteen degrees"),
        cue(125, "thanks for watching and see you next week"),
    )

    @Test
    fun `cues become timed blocks of about twenty seconds and parse back to the same starts`() {
        val blocks = TranscriptText.blocks(cues)
        assertEquals(listOf(0, 25, 70, 125), blocks.map { it.startSec })
        val rendered = TranscriptText.render(blocks)
        assertTrue(rendered.startsWith("[0:00] welcome to the show"))
        assertEquals(blocks, TranscriptText.parse(rendered))
    }

    @Test
    fun `an hour into a talk the stamp carries hours`() {
        assertEquals("1:02:03", TranscriptText.stamp(3723))
        assertEquals(3723, TranscriptText.parse("[1:02:03] hello").single().startSec)
    }

    @Test
    fun `a long transcript is cut evenly to fit the budget`() {
        val long = (0 until 100).map { cue(it * 30, "x".repeat(400)) }
        val rendered = TranscriptText.render(TranscriptText.blocks(long), budget = 10_000)
        assertTrue(rendered.length < 12_000)
        assertEquals(100, rendered.lines().size)
    }

    @Test
    fun `a digest keeps three lines, strips markup and puts the first chapter at zero`() {
        val raw = TranscriptDigest(
            tldr = listOf(
                "- **Sleep** matters [see](https://x.example)",
                "",
                "Cold rooms help.",
                "Light helps.",
                "A fourth.",
            ),
            chapters = listOf(
                DigestChapter(40, "Cold bedroom"),
                DigestChapter(100, "Morning light"),
                DigestChapter(130, "Morning light"),
                DigestChapter(9999, "Past the end"),
                DigestChapter(150, ""),
            ),
        )
        val tidy = DigestRules.tidyDigest(durationSec = 600, raw = raw)
        assertEquals(listOf("Sleep matters see", "Cold rooms help.", "Light helps."), tidy.tldr)
        assertEquals(listOf(0 to "Cold bedroom", 100 to "Morning light"), tidy.chapters.map { it.startSec to it.title })
    }

    @Test
    fun `a chapter second the model rounded is moved to the line that really starts there`() {
        val raw = TranscriptDigest(listOf("x"), listOf(DigestChapter(0, "Start"), DigestChapter(180, "Middle")))
        val tidy = DigestRules.tidyDigest(600, raw, blockStarts = listOf(0, 20, 221, 400))
        assertEquals(listOf(0, 221), tidy.chapters.map { it.startSec })
    }

    @Test
    fun `chapters closer than a minute are dropped and no more than twelve are kept`() {
        val many = (0 until 30).map { DigestChapter(it * 70, "Part $it") } + DigestChapter(5, "Too close")
        val tidy = DigestRules.tidyDigest(durationSec = 4000, raw = TranscriptDigest(listOf("x"), many))
        assertEquals(12, tidy.chapters.size)
        assertFalse(tidy.chapters.any { it.title == "Too close" })
    }

    @Test
    fun `an answer keeps only moments whose quote is really in the transcript near that time`() {
        val rendered = TranscriptText.render(TranscriptText.blocks(cues))
        val raw = TranscriptAnswer(
            found = true,
            answer = "They say the bedroom should be about eighteen degrees.",
            moments = listOf(
                TranscriptMoment(72, "temperature of the bedroom should be around eighteen degrees"),
                TranscriptMoment(5, "the dog ate my homework yesterday afternoon"),
                TranscriptMoment(130, "thanks"),
            ),
        )
        val tidy = DigestRules.tidyAnswer(rendered, raw)
        assertTrue(tidy.found)
        assertEquals(listOf(70), tidy.moments.map { it.atSec })
    }

    @Test
    fun `a moment slightly off in time snaps to the block that holds the words`() {
        val rendered = TranscriptText.render(TranscriptText.blocks(cues))
        val moment = TranscriptMoment(70, "second thing is light in the morning")
        val raw = TranscriptAnswer(true, "Light in the morning.", listOf(moment))
        assertEquals(listOf(25), DigestRules.tidyAnswer(rendered, raw).moments.map { it.atSec })
    }

    @Test
    fun `an answer that was not found carries no moments`() {
        val rendered = TranscriptText.render(TranscriptText.blocks(cues))
        val moment = TranscriptMoment(5, "cold bedroom helps you fall asleep")
        val raw = TranscriptAnswer(false, "They do not say.", listOf(moment))
        val tidy = DigestRules.tidyAnswer(rendered, raw)
        assertFalse(tidy.found)
        assertTrue(tidy.moments.isEmpty())
    }
}
