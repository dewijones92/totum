package com.dewijones92.totum.data.digest

import com.dewijones92.totum.data.transcript.Cue
import kotlinx.serialization.Serializable

@Serializable
public data class DigestChapter(val startSec: Int, val title: String)

@Serializable
public data class TranscriptDigestRequest(
    val title: String,
    val author: String = "",
    val durationSec: Int,
    val transcript: String,
)

@Serializable
public data class TranscriptDigest(val tldr: List<String>, val chapters: List<DigestChapter>)

@Serializable
public data class TranscriptAskRequest(
    val title: String,
    val durationSec: Int,
    val question: String,
    val transcript: String,
)

@Serializable
public data class TranscriptMoment(val atSec: Int, val quote: String)

@Serializable
public data class TranscriptAnswer(val found: Boolean, val answer: String, val moments: List<TranscriptMoment>)

public data class TextBlock(val startSec: Int, val text: String)

public object TranscriptText {
    public const val DEFAULT_BUDGET: Int = 100_000
    private const val MIN_WORD = 4
    private const val NEIGHBOURS = 1
    private const val GLIMPSE = 100
    private const val BLOCK_SECONDS = 20
    private const val BLOCK_CHARS = 420
    private const val MIN_PER_BLOCK = 80
    private const val SECONDS_PER_HOUR = 3600
    private const val SECONDS_PER_MINUTE = 60

    public fun blocks(cues: List<Cue>): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()
        var start = -1
        val text = StringBuilder()
        cues.forEach { cue ->
            val at = (cue.startMs / MS).toInt()
            val line = cue.text.replace(Regex("\\s+"), " ").trim()
            if (line.isEmpty()) return@forEach
            if (start >= 0 && (at - start >= BLOCK_SECONDS || text.length >= BLOCK_CHARS)) {
                blocks += TextBlock(start, text.toString())
                text.clear()
                start = -1
            }
            if (start < 0) start = at
            if (text.isNotEmpty()) text.append(' ')
            text.append(line)
        }
        if (start >= 0) blocks += TextBlock(start, text.toString())
        return blocks
    }

    public fun render(blocks: List<TextBlock>, budget: Int = DEFAULT_BUDGET): String {
        val limit = if (blocks.isEmpty()) budget else maxOf(MIN_PER_BLOCK, budget / blocks.size)
        return blocks.joinToString("\n") { "[${stamp(it.startSec)}] ${it.text.take(limit)}" }
    }

    public fun fit(rendered: String, budget: Int = DEFAULT_BUDGET): String =
        if (rendered.length <= budget) rendered else render(parse(rendered), budget)

    public fun forQuestion(blocks: List<TextBlock>, question: String, budget: Int = DEFAULT_BUDGET): String {
        val full = render(blocks, Int.MAX_VALUE)
        if (full.length <= budget) return full
        val wanted = question.lowercase().split(Regex("[^\\p{L}\\p{N}']+"))
            .filter { it.length >= MIN_WORD && it !in STOP_WORDS }.toSet()
        val scores = blocks.map { block -> block.text.lowercase().let { said -> wanted.count { it in said } } }
        val chosen = sortedSetOf<Int>()
        var used = 0
        for (i in blocks.indices.filter { scores[it] > 0 }.sortedWith(compareBy({ -scores[it] }, { it }))) {
            val group = (i - NEIGHBOURS..i + NEIGHBOURS).filter { it in blocks.indices && it !in chosen }
            val cost = group.sumOf { blocks[it].text.length + STAMP_ROOM }
            if (used + cost > budget * SELECTED_SHARE) break
            chosen += group
            used += cost
        }
        val room = ((budget - used) / maxOf(1, blocks.size - chosen.size)).coerceAtMost(GLIMPSE)
        return blocks.indices.joinToString("\n") { i ->
            val text = if (i in chosen) blocks[i].text else blocks[i].text.take(maxOf(room, 0))
            "[${stamp(blocks[i].startSec)}] $text"
        }
    }

    public fun stamp(seconds: Int): String {
        val h = seconds / SECONDS_PER_HOUR
        val m = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val s = seconds % SECONDS_PER_MINUTE
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    public fun parse(rendered: String): List<TextBlock> = rendered.lineSequence().mapNotNull { line ->
        STAMPED.matchEntire(line)?.let { match ->
            val parts = match.groupValues[1].split(':').map(String::toInt)
            val seconds = parts.fold(0) { total, part -> total * SECONDS_PER_MINUTE + part }
            TextBlock(seconds, match.groupValues[2])
        }
    }.toList()

    private const val STAMP_ROOM = 12
    private const val SELECTED_SHARE = 0.7
    private val STOP_WORDS = setOf(
        "what", "when", "where", "which", "does", "they", "about", "this", "that", "with", "from", "have", "were",
        "said", "says", "video", "episode", "talk", "mention", "mentioned",
    )
    private val STAMPED = Regex("""\[(\d+(?::\d{2}){1,2})] (.*)""")
    private const val MS = 1000L
}
