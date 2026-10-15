package com.dewijones92.totum.data.transcript

import com.dewijones92.totum.common.SubtitleTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

public data class Cue(val startMs: Long, val endMs: Long, val text: String)

public enum class TranscriptFormat { VTT, SRT, JSON }

public object TranscriptParser {

    private val timing = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{3})""")
    private val tags = Regex("""<[^>]+>""")

    public fun parse(body: String, format: TranscriptFormat, rolling: Boolean = false): List<Cue> = when (format) {
        TranscriptFormat.VTT, TranscriptFormat.SRT -> timed(body).let { if (rolling) collapseRolling(it) else it }
        TranscriptFormat.JSON -> json(body).sortedBy { it.startMs }
    }

    public fun formatOf(mimeType: String?, url: String): TranscriptFormat? {
        val type = mimeType.orEmpty().lowercase()
        val path = url.substringBefore('?').lowercase()
        return when {
            "vtt" in type || path.endsWith(".vtt") -> TranscriptFormat.VTT
            "subrip" in type || "srt" in type || path.endsWith(".srt") -> TranscriptFormat.SRT
            "json" in type || path.endsWith(".json") -> TranscriptFormat.JSON
            else -> null
        }
    }

    private fun timed(body: String): List<Cue> {
        val lines = body.replace("\r\n", "\n").split('\n')
        val cues = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val match = timing.find(lines[i])
            if (match == null) {
                i++
                continue
            }
            val text = StringBuilder()
            i++
            while (continuesCue(lines, i)) {
                val clean = decode(lines[i].replace(tags, "")).trim()
                if (clean.isNotEmpty()) text.append(if (text.isEmpty()) clean else " $clean")
                i++
            }
            if (text.isNotEmpty()) cues += Cue(ms(match.groupValues[1]), ms(match.groupValues[2]), text.toString())
        }
        return cues
    }

    private fun continuesCue(lines: List<String>, i: Int): Boolean =
        i < lines.size && lines[i].isNotEmpty() && timing.find(lines[i]) == null && !isCueNumber(lines, i)

    private fun decode(text: String): String = ENTITIES.entries.fold(
        text
    ) { acc, (entity, char) -> acc.replace(entity, char) }

    private fun isCueNumber(lines: List<String>, i: Int): Boolean =
        lines[i].trim().all { it.isDigit() } && lines.getOrNull(i + 1)?.let { timing.find(it) } != null

    private fun collapseRolling(cues: List<Cue>): List<Cue> {
        val out = mutableListOf<Cue>()
        cues.forEach { cue ->
            val last = out.lastOrNull()
            when {
                last != null && cue.text == last.text ->
                    out[out.size - 1] = last.copy(endMs = maxOf(last.endMs, cue.endMs))
                last != null && cue.text.startsWith("${last.text} ") && cue.startMs - last.startMs < ROLLING_MS ->
                    out[out.size - 1] = cue.copy(startMs = last.startMs)
                last != null && ' ' in cue.text && last.text.endsWith(cue.text) -> Unit
                else -> {
                    val fresh = last?.let { stripOverlap(it.text, cue.text) } ?: cue.text
                    if (fresh.isNotBlank()) out += cue.copy(text = fresh)
                }
            }
        }
        return out
    }

    private fun stripOverlap(previous: String, next: String): String {
        val longest = (minOf(previous.length, next.length) downTo 1).firstOrNull { n ->
            previous.endsWith(next.take(n)) && next.take(n).contains(' ')
        }
        return if (longest == null) next else next.drop(longest).trim()
    }

    private fun json(body: String): List<Cue> {
        val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return emptyList()
        val segments = root["segments"] as? JsonArray ?: return emptyList()
        return segments.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val start = (o["startTime"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val end = (o["endTime"] as? JsonPrimitive)?.doubleOrNull ?: start
            val text = (o["body"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            text.takeIf { it.isNotEmpty() }?.let { Cue((start * MS).toLong(), (end * MS).toLong(), it) }
        }
    }

    private fun ms(stamp: String): Long {
        val parts = stamp.replace(',', '.').split(':')
        val seconds = parts.last().toDouble()
        val minutes = parts.getOrNull(parts.size - 2)?.toLong() ?: 0
        val hours = if (parts.size == HMS_PARTS) parts[0].toLong() else 0
        return ((hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE) * MS).toLong() + (seconds * MS).toLong()
    }

    private const val MS = 1_000.0
    private val ENTITIES = linkedMapOf(
        "&lt;" to "<",
        "&gt;" to ">",
        "&quot;" to "\"",
        "&#39;" to "'",
        "&nbsp;" to " ",
        "&amp;" to "&",
    )
    private const val ROLLING_MS = 3_000L
    private const val HMS_PARTS = 3
    private const val SECONDS_PER_HOUR = 3_600
    private const val SECONDS_PER_MINUTE = 60
}

public fun pickTranscriptTrack(tracks: List<SubtitleTrack>, language: String): SubtitleTrack? {
    val wanted = language.substringBefore('-').lowercase()
    val (human, auto) = tracks.partition { !it.autoGenerated }
    return human.firstOrNull { it.baseLanguage == wanted }
        ?: auto.firstOrNull { it.baseLanguage == wanted }
        ?: human.firstOrNull()
}

public fun List<Cue>.indexAt(positionMs: Long): Int = indexOfLast { it.startMs <= positionMs }
