package com.dewijones92.totum.ui.common

import com.dewijones92.totum.innertube.music.RadioSeed

sealed interface MusicPage {
    val label: String

    data class Album(val browseId: String?, val playlistId: String? = null, val title: String) : MusicPage {
        override val label: String get() = "album ${browseId ?: playlistId} \"$title\""
    }

    data class Artist(val browseId: String?, val name: String, val radio: RadioSeed? = null) : MusicPage {
        override val label: String get() = "artist ${browseId ?: "by-name"} \"$name\""
    }

    data class Playlist(val playlist: com.dewijones92.totum.innertube.playlists.Playlist) : MusicPage {
        override val label: String get() = "playlist ${playlist.browseId} \"${playlist.title}\""
    }
}
