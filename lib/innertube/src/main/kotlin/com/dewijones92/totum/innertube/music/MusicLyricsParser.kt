package com.dewijones92.totum.innertube.music

import kotlinx.serialization.json.JsonObject

public data class Lyrics(public val text: String, public val source: String?)

internal object MusicLyricsParser {

    private const val LYRICS_PAGE = "MUSIC_PAGE_TYPE_TRACK_LYRICS"

    fun lyricsBrowseId(body: String): String? {
        var found: String? = null
        parseMusicRoot(body)?.collectEach("tabRenderer") { tab ->
            val browse = tab.obj("endpoint")?.obj("browseEndpoint") ?: return@collectEach
            if (found == null && browse.pageType() == LYRICS_PAGE) found = browse.str("browseId")
        }
        return found
    }

    fun lyrics(body: String): Lyrics? {
        val shelf = parseMusicRoot(body)?.firstObject("musicDescriptionShelfRenderer") ?: return null
        val text = shelf.obj("description").text() ?: return null
        return Lyrics(text, shelf.obj("footer").text())
    }

    private fun JsonObject.pageType(): String? =
        obj("browseEndpointContextSupportedConfigs")?.obj("browseEndpointContextMusicConfig")?.str("pageType")
}
