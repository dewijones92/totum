package com.dewijones92.totum.data.digest

import com.dewijones92.totum.data.transcript.Cue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TranscriptTextTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun block(sec: Int, text: String) = TextBlock(sec, text)

    private val long: List<TextBlock> = (0 until 200).map { block(it * 20, "filler words about nothing ".repeat(8) + it) }
        .toMutableList().also {
            it[120] = block(2400, "the mitochondria is the powerhouse of the cell and everyone should know it")
        }

    @Test
    fun `a transcript that fits is sent whole and in order`() {
        val short = listOf(block(0, "hello there"), block(20, "general kenobi"))

        assertEquals("[0:00] hello there\n[0:20] general kenobi", TranscriptText.forQuestion(short, "anything at all"))
    }

    @Test
    fun `a long transcript keeps the blocks that share the question's words whole, with their neighbours`() {
        val sent = TranscriptText.forQuestion(long, "What did they say about the mitochondria?", budget = 6_000)

        val lines = sent.lines()
        assertEquals(200, lines.size)
        assertTrue(lines.any { it.contains("the mitochondria is the powerhouse of the cell and everyone should know it") })
        val neighbours = listOf(lines[119], lines[121])
        assertTrue(neighbours.joinToString(), neighbours.all { it.length > 100 })
        assertTrue("a block far away is only a glimpse", lines[10].length < 120)
        assertTrue("[40:00] is where it was said", lines[120].startsWith("[40:00]"))
    }

    @Test
    fun `what is sent stays within the budget`() {
        val sent = TranscriptText.forQuestion(long, "mitochondria powerhouse cell", budget = 6_000)

        assertTrue("${sent.length} chars", sent.length <= 6_000 + long.size * 12)
    }

    @Test
    fun `words that say nothing about the subject do not pull a block in`() {
        val chatty = (0 until 200).map { block(it * 20, "they said this video talk about what ".repeat(10)) }

        val sent = TranscriptText.forQuestion(chatty, "What did they say about this video?", budget = 4_000)

        assertTrue(sent.lines().all { it.length < 140 })
    }

    @Test
    fun `a question with no matching words still gets a glimpse of every block`() {
        val sent = TranscriptText.forQuestion(long, "zebras?", budget = 6_000)

        assertEquals(200, sent.lines().size)
        assertTrue(sent.lines().all { it.length < 140 })
        assertTrue(sent.lines().first().startsWith("[0:00] "))
    }

    @Test
    fun `fitting a rendered transcript cuts every line evenly and keeps every stamp`() {
        val rendered = TranscriptText.render(long, Int.MAX_VALUE)

        val fitted = TranscriptText.fit(rendered, budget = 10_000)

        assertTrue(fitted.length < rendered.length)
        assertEquals(long.map { it.startSec }, TranscriptText.parse(fitted).map { it.startSec })
        assertEquals(rendered, TranscriptText.fit(rendered, budget = Int.MAX_VALUE))
    }

    @Test
    fun `blocks with nothing said are skipped and a long cue starts a new block`() {
        val cues = listOf(
            Cue(0, 1_000, "  "),
            Cue(1_000, 2_000, "first   line"),
            Cue(2_000, 3_000, "x".repeat(430)),
            Cue(3_000, 4_000, "next"),
        )

        val blocks = TranscriptText.blocks(cues)

        assertEquals(listOf(1, 3), blocks.map { it.startSec })
        assertEquals("first line " + "x".repeat(430), blocks[0].text)
        assertEquals("next", blocks[1].text)
        assertTrue(TranscriptText.blocks(emptyList()).isEmpty())
    }

    @Test
    fun `an empty transcript renders as nothing`() {
        assertEquals("", TranscriptText.render(emptyList()))
        assertEquals("", TranscriptText.forQuestion(emptyList(), "anything"))
    }

    @Test
    fun `a summary line over the limit is cut at a sentence end, not mid-word`() {
        val sentence = "This talk explains why the cold bedroom helps people fall asleep faster than a warm one does. "
        val raw = TranscriptDigest(listOf(sentence.repeat(4) + "and then it just keeps going without stopping"), emptyList())

        val line = DigestRules.tidyDigest(600, raw).tldr.single()

        assertTrue(line, line.length <= 220)
        assertTrue(line, line.endsWith("."))
        assertFalse(line.contains("keeps going"))
    }

    @Test
    fun `a summary line with no sentence end is cut at a space`() {
        val raw = TranscriptDigest(listOf("word ".repeat(80)), emptyList())

        val line = DigestRules.tidyDigest(600, raw).tldr.single()

        assertTrue(line.length <= 220)
        assertTrue(line.endsWith("word"))
    }

    @Test
    fun `the digest store round-trips and a corrupt file starts afresh`() {
        val file = File(tmp.root, "digests.json")
        val digest = StoredDigest(TranscriptDigest(listOf("a line"), listOf(DigestChapter(0, "Start"))), at = 9, blocks = 4)

        FileDigestStore(file).save(mapOf("item-1" to digest))

        assertEquals(mapOf("item-1" to digest), FileDigestStore(file).load())
        file.writeText("{broken")
        assertEquals(emptyMap<String, StoredDigest>(), FileDigestStore(file).load())
        assertEquals(emptyMap<String, StoredDigest>(), FileDigestStore(File(tmp.root, "none.json")).load())
        FileDigestStore(File(tmp.root, "no/such/dir/d.json")).save(mapOf("x" to digest))
    }
}
