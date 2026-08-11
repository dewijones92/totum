package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.parseClockToSeconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses YouTube Music search results.
 *
 * A different renderer from every other search in this app — `musicResponsiveListItemRenderer`,
 * whose text lives in `flexColumns` rather than in named fields — so it is its own parser rather
 * than a reuse of `SearchResultsParser`. Walks the whole tree, so a section reshuffle changes
 * nothing, and dedupes by video id keeping first-seen (relevance) order.
 *
 * **The second column is a `•`-separated list whose shape depends on the request**, verified
 * against the live API on 2026-08-11:
 *
 * ```
 * songs filter:  ["Nina Simone", " • ", "I Put A Spell On You", " • ", "2:54"]
 * no filter:     ["Video", " • ", "M M P F", " • ", "2.6M views", " • ", "2:58"]
 * ```
 *
 * So it is read by SHAPE rather than by position: the duration is the segment that looks like a
 * clock, a leading type word is dropped, counts are dropped, and what remains is artist then
 * album. Reading `segments[0]` as the artist would have credited half the results to "Video".
 */
internal object MusicSearchParser {

    private val json = Json { ignoreUnknownKeys = true }

    /** Rows that are not playable — albums, artists, playlists — carry no video id and are dropped. */
    fun songs(body: String): List<MusicSong> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val out = LinkedHashMap<String, MusicSong>()
        collect(root, ITEM_RENDERER) { renderer ->
            renderer.toSongOrNull()?.let { out.putIfAbsent(it.videoId, it) }
        }
        return out.values.toList()
    }

    private fun collect(node: JsonElement, key: String, onNode: (JsonObject) -> Unit) {
        when (node) {
            is JsonObject -> {
                (node[key] as? JsonObject)?.let(onNode)
                node.values.forEach { collect(it, key, onNode) }
            }
            is JsonArray -> node.forEach { collect(it, key, onNode) }
            else -> Unit
        }
    }

    private fun JsonObject.toSongOrNull(): MusicSong? {
        val videoId = firstVideoId(this) ?: return null
        val watchUrl = FeedVideo.watchUrlFor(videoId) ?: return null
        val columns = flexColumns()
        val title = columns.firstOrNull()?.joinToString("")?.trim()?.ifBlank { null } ?: return null
        val details = columns.getOrNull(1).orEmpty().segments()
        return MusicSong(
            videoId = videoId,
            title = title,
            artist = details.firstOrNull { it.isCredit() },
            album = details.filter { it.isCredit() }.drop(1).firstOrNull(),
            durationSeconds = details.lastOrNull { it.isClock() }?.let(::parseClockToSeconds),
            thumbnailUrl = bestThumbnailUrl(),
            watchUrl = watchUrl,
            // Its own column, and only in a songs search. "276M plays" is the closest thing music
            // has to a view count, and the row is noticeably barer without it.
            playsText = columns.getOrNull(2)?.joinToString("")?.trim()?.ifBlank { null },
        )
    }

    /** The runs of each flex column, in order. */
    private fun JsonObject.flexColumns(): List<List<String>> =
        (this["flexColumns"] as? JsonArray).orEmpty().mapNotNull { column ->
            val renderer = (column as? JsonObject)?.get(COLUMN_RENDERER) as? JsonObject ?: return@mapNotNull null
            val runs = ((renderer["text"] as? JsonObject)?.get("runs") as? JsonArray).orEmpty()
            runs.mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
        }

    /** The `•`-separated pieces of a column, with the separators and blanks removed. */
    private fun List<String>.segments(): List<String> =
        joinToString("").split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * A segment naming somebody or something, rather than a type, a count or a clock.
     *
     * Type words are dropped by name because an unfiltered search leads with one, and a year is
     * dropped because an album row ends with one — neither is a credit, and both would otherwise
     * be read as the artist or the album.
     */
    private fun String.isCredit(): Boolean =
        !isClock() && !isCount() && lowercase() !in TYPE_WORDS && !isYear()

    private fun String.isClock(): Boolean = matches(CLOCK)

    private fun String.isCount(): Boolean = COUNT_WORDS.any { endsWith(it, ignoreCase = true) }

    private fun String.isYear(): Boolean = matches(YEAR)

    /**
     * The id from the first `watchEndpoint` anywhere in the row.
     *
     * Anywhere on purpose: it appears on the overlay's play button AND on the title run, and which
     * of them is present varies by row type. Searching for the field rather than a path means a
     * layout change cannot quietly turn every song into an unplayable one.
     */
    private fun firstVideoId(node: JsonElement): String? = when (node) {
        is JsonObject -> {
            val here = (node["watchEndpoint"] as? JsonObject)?.get("videoId")?.jsonPrimitive?.contentOrNull
            here ?: node.values.firstNotNullOfOrNull { firstVideoId(it) }
        }
        is JsonArray -> node.firstNotNullOfOrNull { firstVideoId(it) }
        else -> null
    }

    private const val ITEM_RENDERER = "musicResponsiveListItemRenderer"
    private const val COLUMN_RENDERER = "musicResponsiveListItemFlexColumnRenderer"
    private const val SEPARATOR = "•"
    private val CLOCK = Regex("""\d{1,2}(:\d{2}){1,2}""")
    private val YEAR = Regex("""\d{4}""")
    private val COUNT_WORDS = listOf("views", "plays", "audience", "subscribers", "songs")
    private val TYPE_WORDS = setOf(
        "song", "video", "album", "artist", "playlist", "single", "ep", "episode", "podcast", "profile",
    )
}

/**
 * The largest thumbnail a row offers; music rows list them smallest-first.
 *
 * A file-level function rather than a member: it is about pictures, not about rows, and every
 * candidate is `{url, width, height}` wherever it appears in the tree.
 */
private fun JsonObject.bestThumbnailUrl(): HttpUrl? {
    val candidates = mutableListOf<Pair<Int, String>>()
    fun walk(node: JsonElement) {
        when (node) {
            is JsonObject -> {
                val url = node["url"]?.jsonPrimitive?.contentOrNull
                val width = node["width"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                if (url != null && width != null) candidates += width to url
                node.values.forEach(::walk)
            }
            is JsonArray -> node.forEach(::walk)
            else -> Unit
        }
    }
    (this["thumbnail"] as? JsonObject)?.let(::walk)
    return candidates.maxByOrNull { it.first }?.second?.let(HttpUrl::parse)
}
