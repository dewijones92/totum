package com.dewijones92.totum.data.torrent

internal class ReleaseName(name: String) {
    val words: List<String> = name.split(SEPARATORS).filter { it.isNotEmpty() }
    private val marker: Int = words.indexOfFirst { it.isMarker() }

    val title: List<String> get() = if (marker < 0) words else words.take(marker)
    val tail: List<String> get() = if (marker < 0) words else words.drop(marker)
    val singleEpisode: Boolean get() = words.any { SINGLE_EPISODE.matches(it) }

    val key: String?
        get() {
            if (marker <= 0) return null
            val at = words[marker].lowercase().takeIf { YEAR.matches(it) || EPISODE.matches(it) }
            return (words.take(marker).map(String::lowercase) + listOfNotNull(at)).joinToString(" ")
        }

    private fun String.isMarker(): Boolean = lowercase().let {
        YEAR.matches(it) || EPISODE.matches(it) || it in QUALITY_WORDS
    }

    private companion object {
        val SEPARATORS = Regex("""[\s.\-_\[\]()]+""")
        val YEAR = Regex("""(19|20)\d\d""")
        val EPISODE = Regex("""s\d{1,2}(e\d{1,3})?""")
        val SINGLE_EPISODE = Regex("""[sS]\d{1,2}[eE]\d{1,3}""")
        val QUALITY_WORDS = setOf("2160p", "1080p", "720p", "576p", "480p", "4k", "bluray", "webrip", "hdtv", "dvdrip")
    }
}
