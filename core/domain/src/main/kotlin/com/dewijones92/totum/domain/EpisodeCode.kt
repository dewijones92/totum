package com.dewijones92.totum.domain

public data class EpisodeCode(val season: Int, val episode: Int)

public fun episodeCodeOf(name: String): EpisodeCode? {
    SEASON_EPISODE.find(name)?.let { return EpisodeCode(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    CROSS_FORM.find(name)?.let { return EpisodeCode(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    return null
}

public fun showTitleOf(release: String): String? {
    val cut = SEASON_MARK.find(release)?.range?.first ?: return null
    return release.substring(0, cut)
        .replace(YEAR, " ")
        .replace(SEPARATORS, " ")
        .trim()
        .lowercase()
        .replace(SPACES, " ")
        .ifBlank { null }
}

private val SEASON_EPISODE = Regex("""[Ss](\d{1,2})[Ee](\d{1,3})""")
private val CROSS_FORM = Regex("""(?<!\d)(\d{1,2})[xX](\d{2})(?!\d)""")
private val SEASON_MARK = Regex("""(?i)\bS\d{1,2}(E\d{1,3})?\b|\bSeason\s*\d|(?<!\d)\d{1,2}x\d{2}(?!\d)""")
private val YEAR = Regex("""[(\[]?\b(19|20)\d{2}\b[)\]]?""")
private val SEPARATORS = Regex("""[._\-()\[\]]""")
private val SPACES = Regex("""\s+""")
