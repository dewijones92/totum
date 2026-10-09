package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.feeds.parseClockToSeconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The runs of each flex column, in order. */
internal fun JsonObject.flexColumns(): List<JsonObject> =
    (this["flexColumns"] as? JsonArray).orEmpty().mapNotNull { column ->
        (column as? JsonObject)?.obj(FLEX_COLUMN)?.obj("text")
    }

internal fun JsonObject.fixedColumnText(): String? =
    (this["fixedColumns"] as? JsonArray).orEmpty().firstNotNullOfOrNull { column ->
        (column as? JsonObject)?.obj(FIXED_COLUMN)?.obj("text").text()
    }

/** The `•`-separated pieces of a column, with the separators and blanks removed. */
internal fun String.segments(): List<String> = split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

internal fun String.isClock(): Boolean = matches(CLOCK)

internal fun String.isYear(): Boolean = matches(YEAR)

internal fun String.isCount(): Boolean = COUNT_WORDS.any { endsWith(it, ignoreCase = true) }

internal fun String.isTypeWord(): Boolean = lowercase() in TYPE_WORDS

/**
 * A segment naming somebody or something, rather than a type, a count or a clock.
 *
 * Type words are dropped by name because an unfiltered search leads with one, and a year is
 * dropped because an album row ends with one — neither is a credit, and both would otherwise
 * be read as the artist or the album.
 */
internal fun String.isCredit(): Boolean = !isClock() && !isCount() && !isTypeWord() && !isYear()

internal fun JsonObject.toSongOrNull(fallbackArt: HttpUrl? = null): MusicSong? {
    val videoId = firstVideoId() ?: return null
    val watchUrl = FeedVideo.watchUrlFor(videoId) ?: return null
    val columns = flexColumns()
    val title = columns.firstOrNull().text() ?: return null
    val details = columns.getOrNull(1).text().orEmpty().segments()
    val credits = details.filter { it.isCredit() }
    val links = columns.drop(1).flatMap { it.linkedRuns() }
    return MusicSong(
        videoId = videoId,
        title = title,
        artist = credits.firstOrNull(),
        album = links.titleOf(ALBUM_ID_PREFIX)
            ?: credits.drop(1).firstOrNull()
            ?: columns.getOrNull(ALBUM_COLUMN).text(),
        durationSeconds = (details.lastOrNull { it.isClock() } ?: fixedColumnText()?.takeIf { it.isClock() })
            ?.let(::parseClockToSeconds),
        thumbnailUrl = obj("thumbnail")?.bestThumbnailUrl() ?: fallbackArt,
        watchUrl = watchUrl,
        // Its own column, and only in a songs search. "276M plays" is the closest thing music
        // has to a view count, and the row is noticeably barer without it.
        playsText = columns.getOrNull(PLAYS_COLUMN).text(),
        artistId = links.idOf(ARTIST_ID_PREFIX),
        albumId = links.idOf(ALBUM_ID_PREFIX),
    )
}

private const val FLEX_COLUMN = "musicResponsiveListItemFlexColumnRenderer"
private const val FIXED_COLUMN = "musicResponsiveListItemFixedColumnRenderer"
internal const val ARTIST_ID_PREFIX = "UC"
internal const val ALBUM_ID_PREFIX = "MPREb_"
private const val PLAYS_COLUMN = 2
private const val ALBUM_COLUMN = 3
private const val SEPARATOR = "•"
private val CLOCK = Regex("""\d{1,2}(:\d{2}){1,2}""")
private val YEAR = Regex("""\d{4}""")
private val COUNT_WORDS = listOf("views", "plays", "audience", "subscribers", "songs", "listeners")
private val TYPE_WORDS = setOf(
    "song", "video", "album", "artist", "playlist", "single", "ep", "episode", "podcast", "profile",
)
