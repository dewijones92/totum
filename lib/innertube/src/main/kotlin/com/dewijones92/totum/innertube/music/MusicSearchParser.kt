package com.dewijones92.totum.innertube.music

import kotlinx.serialization.json.JsonObject

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

    /** Rows that are not playable — albums, artists, playlists — carry no video id and are dropped. */
    fun songs(body: String): List<MusicSong> {
        val root = parseMusicRoot(body) ?: return emptyList()
        val out = LinkedHashMap<String, MusicSong>()
        root.collectEach(ITEM_RENDERER) { renderer ->
            renderer.toSongOrNull()?.let { out.putIfAbsent(it.videoId, it) }
        }
        return out.values.toList()
    }

    fun albums(body: String): List<MusicAlbumRef> {
        val root = parseMusicRoot(body) ?: return emptyList()
        val out = LinkedHashMap<String, MusicAlbumRef>()
        root.collectEach(ITEM_RENDERER) { row -> row.toAlbumRefOrNull()?.let { out.putIfAbsent(it.browseId, it) } }
        return out.values.toList()
    }

    fun artists(body: String): List<MusicArtistRef> {
        val root = parseMusicRoot(body) ?: return emptyList()
        val out = LinkedHashMap<String, MusicArtistRef>()
        root.collectEach(ITEM_RENDERER) { row -> row.toArtistRefOrNull()?.let { out.putIfAbsent(it.browseId, it) } }
        return out.values.toList()
    }

    private fun JsonObject.toAlbumRefOrNull(): MusicAlbumRef? {
        val link = obj("navigationEndpoint")?.firstBrowseLink()?.takeIf { it.pageType == ALBUM_PAGE } ?: return null
        val columns = flexColumns()
        val title = columns.firstOrNull().text() ?: return null
        val details = columns.getOrNull(1).text().orEmpty().segments()
        return MusicAlbumRef(
            browseId = link.browseId,
            title = title,
            artist = details.filter { it.isCredit() }.joinToString(", ").ifBlank { null },
            year = details.lastOrNull { it.isYear() },
            kind = releaseKindOf(details.firstOrNull()),
            thumbnailUrl = obj("thumbnail")?.bestThumbnailUrl(),
        )
    }

    private fun JsonObject.toArtistRefOrNull(): MusicArtistRef? {
        val link = obj("navigationEndpoint")?.firstBrowseLink()?.takeIf { it.pageType == ARTIST_PAGE } ?: return null
        val columns = flexColumns()
        val name = columns.firstOrNull().text() ?: return null
        return MusicArtistRef(
            browseId = link.browseId,
            name = name,
            subtitle = columns.getOrNull(1).text().orEmpty().segments().filterNot { it.isTypeWord() }
                .joinToString(" • ").ifBlank { null },
            thumbnailUrl = obj("thumbnail")?.bestThumbnailUrl(),
        )
    }

    private const val ITEM_RENDERER = "musicResponsiveListItemRenderer"
}

internal const val ALBUM_PAGE = "MUSIC_PAGE_TYPE_ALBUM"
internal const val ARTIST_PAGE = "MUSIC_PAGE_TYPE_ARTIST"

internal fun releaseKindOf(word: String?): MusicReleaseKind = when (word?.lowercase()) {
    "single" -> MusicReleaseKind.SINGLE
    "ep" -> MusicReleaseKind.EP
    else -> MusicReleaseKind.ALBUM
}
