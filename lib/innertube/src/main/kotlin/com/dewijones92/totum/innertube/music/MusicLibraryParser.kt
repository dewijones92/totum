package com.dewijones92.totum.innertube.music

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

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
}
