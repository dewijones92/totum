package com.dewijones92.totum.playback.cast

internal object TvCaptions {
    data class Cleaned(val text: String, val settingsRemoved: Map<String, Int>, val inlineTagsRemoved: Int)

    private val inlineTag = Regex("""<\d{2}:\d{2}(:\d{2})?\.\d{3}>|</?c(\.[^>]*)?>""")
    private val whitespace = Regex("""\s+""")
    private const val TIMING_PARTS = 3

    fun clean(vtt: String): Cleaned {
        val removed = mutableMapOf<String, Int>()
        var tags = 0
        val lines = vtt.split('\n').map { line ->
            if ("-->" in line) {
                val parts = line.trim().split(whitespace)
                parts.drop(TIMING_PARTS).forEach { removed.merge(it.substringBefore(':'), 1, Int::plus) }
                if (parts.size > TIMING_PARTS) parts.take(TIMING_PARTS).joinToString(" ") else line
            } else {
                tags += inlineTag.findAll(line).count()
                line.replace(inlineTag, "")
            }
        }
        return Cleaned(lines.joinToString("\n"), removed, tags)
    }
}
