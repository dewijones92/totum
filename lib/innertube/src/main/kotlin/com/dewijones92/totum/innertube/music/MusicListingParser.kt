package com.dewijones92.totum.innertube.music

import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken

internal object MusicListingParser {

    fun songs(body: String): Page<MusicSong> {
        val root = parseMusicRoot(body) ?: return Page.empty()
        val songs = LinkedHashMap<String, MusicSong>()
        root.collectEach("musicResponsiveListItemRenderer") { row ->
            row.toSongOrNull()?.let { songs.putIfAbsent(it.videoId, it) }
        }
        var next: String? = null
        root.collectEach("continuationItemRenderer") { item ->
            next = next ?: item.obj("continuationEndpoint")?.obj("continuationCommand")?.str("token")
        }
        return Page(songs.values.toList(), next?.let(::PageToken))
    }

    fun releases(body: String, artist: String?): List<MusicAlbumRef> {
        val root = parseMusicRoot(body) ?: return emptyList()
        val releases = LinkedHashMap<String, MusicAlbumRef>()
        root.collectEach("musicTwoRowItemRenderer") { item ->
            val link = item.obj("navigationEndpoint")?.firstBrowseLink()?.takeIf { it.pageType == ALBUM_PAGE }
                ?: return@collectEach
            item.toAlbumRef(link.browseId, artist)?.let { releases.putIfAbsent(it.browseId, it) }
        }
        return releases.values.toList()
    }
}
