package com.dewijones92.totum.innertube.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal object MusicLibraryParser {

    fun libraryAlbums(body: String): List<MusicAlbumRef> {
        val root = parseMusicRoot(body) ?: return emptyList()
        val out = LinkedHashMap<String, MusicAlbumRef>()
        root.collectEach("tileRenderer") { tile ->
            val metadata = tile.obj("metadata")?.obj("tileMetadataRenderer") ?: return@collectEach
            val titleBlock = metadata.obj("title") ?: return@collectEach
            val link = titleBlock.firstBrowseLink()?.takeIf { it.pageType == ALBUM_PAGE } ?: return@collectEach
            val lines = (metadata["lines"] as? JsonArray).orEmpty().mapNotNull { it.lineText() }
            val details = lines.drop(1).joinToString(" • ").segments()
            out.putIfAbsent(
                link.browseId,
                MusicAlbumRef(
                    browseId = link.browseId,
                    title = titleBlock.text() ?: return@collectEach,
                    artist = lines.firstOrNull(),
                    year = details.lastOrNull { it.isYear() },
                    kind = releaseKindOf(details.firstOrNull()),
                    thumbnailUrl = tile.obj("header")?.bestThumbnailUrl(),
                ),
            )
        }
        return out.values.toList()
    }

    private fun JsonElement.lineText(): String? {
        var text: String? = null
        collectEach("lineItemRenderer") { item -> if (text == null) text = item.obj("text").text() }
        return text
    }

    fun libraryArtists(body: String): List<LibraryArtist> {
        val root = parseMusicRoot(body) ?: return emptyList()
        val out = LinkedHashMap<String, LibraryArtist>()
        root.collectEach("tileRenderer") { tile ->
            if (tile.str("contentType") != CHANNEL_TILE) return@collectEach
            val name = tile.obj("metadata")?.obj("tileMetadataRenderer")?.obj("title").text() ?: return@collectEach
            val radio = tile.obj("onSelectCommand")?.obj("watchPlaylistEndpoint")?.str("playlistId")
                ?: tile.str("contentId")?.takeIf { it.startsWith(RADIO_PREFIX) }
            out.putIfAbsent(
                name,
                LibraryArtist(
                    name = name,
                    thumbnailUrl = tile.obj("header")?.bestThumbnailUrl(),
                    radio = radio?.let { RadioSeed(null, it) },
                ),
            )
        }
        return out.values.toList()
    }

    fun songCount(body: String): Int? {
        var count: Int? = null
        parseMusicRoot(body)?.collectEach("entityMetadataRenderer") { header ->
            if (count == null) count = header["bylines"]?.firstSongCount()
        }
        return count
    }

    private fun JsonElement.firstSongCount(): Int? = when (this) {
        is JsonPrimitive -> contentOrNull?.asSongCount()
        is JsonObject -> values.firstNotNullOfOrNull { it.firstSongCount() }
        is JsonArray -> firstNotNullOfOrNull { it.firstSongCount() }
    }

    private fun String.asSongCount(): Int? =
        SONG_COUNT.matchEntire(trim())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()

    private val SONG_COUNT = Regex("""([\d,]+) songs?""")
    private const val CHANNEL_TILE = "TILE_CONTENT_TYPE_CHANNEL"
    private const val RADIO_PREFIX = "RD"
}
