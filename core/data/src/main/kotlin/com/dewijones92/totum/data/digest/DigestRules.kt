package com.dewijones92.totum.data.digest

import com.dewijones92.totum.data.ask.squashed

public object DigestRules {
    public const val MAX_TLDR_LINES: Int = 3
    public const val MAX_CHAPTERS: Int = 12
    public const val MIN_CHAPTER_GAP_SEC: Int = 60
    private const val MAX_LINE = 220
    private const val MAX_TITLE = 70
    private const val MAX_ANSWER = 900
    private const val MAX_MOMENTS = 4
    private const val MIN_QUOTE_WORDS = 3
    private const val QUOTE_MATCH = 0.6
    private const val NEAR_BLOCKS = 2
    private const val MIN_SENTENCE = 40

    private val markdownLink = Regex("""\[([^\]]+)]\((https?://[^)\s]+)\)""")
    private val markup = Regex("""[*_`#>]+""")
    private val bullet = Regex("""^\s*(?:[-•*]|\d+[.)])\s+""")

    public fun tidyDigest(
        durationSec: Int,
        raw: TranscriptDigest,
        blockStarts: List<Int> = emptyList(),
    ): TranscriptDigest = TranscriptDigest(
        tldr = raw.tldr.map(::plain).filter { it.isNotEmpty() }.map(::wholeSentences).take(MAX_TLDR_LINES),
        chapters = tidyChapters(durationSec, raw.chapters, blockStarts),
    )

    private fun tidyChapters(
        durationSec: Int,
        raw: List<DigestChapter>,
        blockStarts: List<Int>,
    ): List<DigestChapter> {
        val sane = raw.filter { it.startSec in 0 until maxOf(durationSec, 1) }
            .map { DigestChapter(snapped(it.startSec, blockStarts), plain(it.title).take(MAX_TITLE)) }
            .filter { it.title.isNotEmpty() }
            .sortedBy { it.startSec }
        val spaced = mutableListOf<DigestChapter>()
        sane.forEach { chapter ->
            val last = spaced.lastOrNull()
            val tooClose = last != null && chapter.startSec - last.startSec < MIN_CHAPTER_GAP_SEC
            val repeats = last != null && last.title.squashed() == chapter.title.squashed()
            if (!tooClose && !repeats) spaced += chapter
        }
        val capped = spaced.take(MAX_CHAPTERS)
        return capped.firstOrNull()?.takeIf { it.startSec != 0 }
            ?.let { listOf(it.copy(startSec = 0)) + capped.drop(1) } ?: capped
    }

    public fun tidyAnswer(rendered: String, raw: TranscriptAnswer): TranscriptAnswer {
        val blocks = TranscriptText.parse(rendered)
        val moments = raw.moments.mapNotNull { confirmed(blocks, it) }
            .distinctBy { it.atSec }.sortedBy { it.atSec }.take(MAX_MOMENTS)
        val answer = plain(raw.answer).take(MAX_ANSWER)
        return TranscriptAnswer(
            found = raw.found && answer.isNotEmpty(),
            answer = answer,
            moments = if (raw.found) moments else emptyList(),
        )
    }

    private fun confirmed(blocks: List<TextBlock>, moment: TranscriptMoment): TranscriptMoment? {
        val words = moment.quote.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).filter { it.isNotEmpty() }
        if (words.size < MIN_QUOTE_WORDS || blocks.isEmpty()) return null
        val nearest = blocks.indexOfLast { it.startSec <= moment.atSec }.coerceAtLeast(0)
        val window = blocks.subList(
            maxOf(0, nearest - NEAR_BLOCKS),
            minOf(blocks.size, nearest + NEAR_BLOCKS + 1)
        )
        val spoken = window.joinToString(" ") { it.text }.lowercase()
            .split(Regex("[^\\p{L}\\p{N}']+")).toSet()
        val found = words.count { it in spoken }
        if (found.toDouble() / words.size < QUOTE_MATCH) return null
        val holder = window.firstOrNull { block ->
            val inBlock = block.text.lowercase().split(Regex("[^\\p{L}\\p{N}']+")).toSet()
            words.count { it in inBlock }.toDouble() / words.size >= QUOTE_MATCH
        } ?: blocks[nearest]
        return TranscriptMoment(holder.startSec, plain(moment.quote).take(MAX_LINE))
    }

    private fun snapped(startSec: Int, blockStarts: List<Int>): Int =
        blockStarts.minByOrNull { kotlin.math.abs(it - startSec) } ?: startSec

    private fun wholeSentences(line: String): String {
        if (line.length <= MAX_LINE) return line
        val end = line.take(MAX_LINE).lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end >= MIN_SENTENCE) line.take(end + 1) else line.take(MAX_LINE).substringBeforeLast(' ')
    }

    private fun plain(text: String): String = text
        .replace(markdownLink) { it.groupValues[1] }
        .replace(bullet, "")
        .replace(markup, "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
