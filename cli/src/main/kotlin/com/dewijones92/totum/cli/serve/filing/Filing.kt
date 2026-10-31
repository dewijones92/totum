package com.dewijones92.totum.cli.serve.filing

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FilePlan(
    val action: Action,
    val shelf: String,
    val folder: String,
    val fileName: String,
    val reason: String,
) {
    @Serializable
    enum class Action {
        @SerialName("movie")
        MOVIE,

        @SerialName("episode")
        EPISODE,

        @SerialName("leave")
        LEAVE,
    }
}

data class FileRequest(val name: String, val sizeBytes: Long?, val context: String, val from: String? = null)

object Filing {
    private val episode =
        Regex("""(?i)(?<![a-z0-9])S(\d{1,2})[ ._-]?E(\d{1,3})(?!\d)|(?<![0-9])S(\d{2})E(\d{2,3})(?!\d)""")
    private val year = Regex("""(?<![0-9])(19\d{2}|20\d{2})(?![0-9])""")
    private val marker = Regex(
        """(?i)(?<![a-z0-9])(S\d{1,2}E?\d*|19\d{2}|20\d{2}|2160p|1080p|720p|480p""" +
            """|x26[45]|hevc|web|bluray|season)(?![a-z0-9])""",
    )
    private val separators = Regex("""[._\-\[\]()]+""")
    private const val CANDIDATES = 3
    private const val FILES_SHOWN = 8
    private const val DEPTH = 3

    fun titleWords(name: String): List<String> {
        val cut = marker.find(name)?.range?.first ?: name.length
        return name.take(cut).replace(separators, " ").lowercase().split(' ').filter { it.isNotBlank() }
    }

    fun words(folder: String): Set<String> =
        folder.replace(separators, " ").lowercase().split(' ').filter { it.isNotBlank() }.toSet()

    fun score(name: String, folder: String): Double {
        val wanted = titleWords(name).toSet()
        if (wanted.isEmpty()) return 0.0
        val have = words(folder)
        return wanted.count { it in have }.toDouble() / wanted.size
    }

    fun isEpisode(name: String): Boolean = episode.containsMatchIn(name)

    fun episodeOf(name: String): Pair<Int, Int>? = episode.find(name)?.groupValues?.let { g ->
        g[SEASON].ifEmpty { g[SEASON_ALT] }.toInt() to g[EPISODE].ifEmpty { g[EPISODE_ALT] }.toInt()
    }

    fun context(name: String, library: LibraryView, from: String? = null): String = buildString {
        val subject = listOfNotNull(from, name).joinToString(" ")
        val first = if (isEpisode(name) || from?.let(::isEpisode) == true) Shelf.SERIES else Shelf.MOVIES
        (listOf(first) + Shelf.entries.filter { it != first }).forEach { shelf ->
            val candidates = library.dirs(shelf, "").sortedByDescending { score(subject, it) }
                .filter { score(subject, it) > 0 }.take(CANDIDATES)
            appendLine("Library \"${shelf.folder}\" top-level folders that look closest:")
            if (candidates.isEmpty()) appendLine("  (none look related)")
            candidates.forEach { appendTree(library, shelf, it, 1) }
            appendLine("Every top-level name in \"${shelf.folder}\": " + library.dirs(shelf, "").joinToString(" | "))
            appendLine(
                "Loose files at the top of \"${shelf.folder}\": " +
                    library.files(shelf, "").take(FILES_SHOWN).joinToString(" | "),
            )
        }
    }

    private fun StringBuilder.appendTree(library: LibraryView, shelf: Shelf, path: String, depth: Int) {
        val indent = "  ".repeat(depth)
        appendLine("$indent$path/")
        library.files(shelf, path).take(FILES_SHOWN).forEach { appendLine("$indent  · $it") }
        if (depth < DEPTH) library.dirs(shelf, path).forEach { appendTree(library, shelf, "$path/$it", depth + 1) }
    }

    fun byRules(name: String, library: LibraryView, from: String? = null): FilePlan {
        val ext = name.substringAfterLast('.', "mkv")
        val subject = listOfNotNull(from, name).joinToString(" ")
        val title = titleWords(subject).joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.ifBlank { name }
        val ep = episodeOf(name) ?: episodeOf(subject)
        if (ep != null) {
            val show = library.dirs(Shelf.SERIES, "").maxByOrNull {
                score(subject, it)
            }?.takeIf { score(subject, it) >= HALF } ?: title
            val season = ep.first
            val seasonDir = library.dirs(Shelf.SERIES, show).firstOrNull { seasonOf(it) == season }
                ?: "Season %02d".format(season)
            return FilePlan(FilePlan.Action.EPISODE, Shelf.SERIES.folder, "$show/$seasonDir", name, "by rules")
        }
        val y = year.find(subject)?.value
        val folder = if (y != null) "$title ($y)" else title
        return FilePlan(FilePlan.Action.MOVIE, Shelf.MOVIES.folder, folder, "$folder.$ext", "by rules")
    }

    fun seasonOf(folder: String): Int? =
        Regex("""(?i)(?:\bS|season\s*|series\s*)(\d{1,2})\b""").find(folder)?.groupValues?.get(1)?.toInt()

    private const val HALF = 0.5
    private const val SEASON = 1
    private const val EPISODE = 2
    private const val SEASON_ALT = 3
    private const val EPISODE_ALT = 4
}
