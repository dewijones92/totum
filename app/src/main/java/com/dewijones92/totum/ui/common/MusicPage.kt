package com.dewijones92.totum.ui.common

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.music.MusicListing
import com.dewijones92.totum.innertube.music.RadioSeed

sealed interface MusicPage {
    val label: String

    data class Album(
        val browseId: String?,
        val playlistId: String? = null,
        val title: String,
        val artist: String? = null,
    ) : MusicPage {
        override val label: String get() = "album ${browseId ?: playlistId} \"$title\""
    }

    data class Artist(val browseId: String?, val name: String, val radio: RadioSeed? = null) : MusicPage {
        override val label: String get() = "artist ${browseId ?: "by-name"} \"$name\""
    }

    data class ArtistSongs(val listing: MusicListing, val artist: String) : MusicPage {
        override val label: String get() = "all songs of \"$artist\" ${listing.browseId}"
    }

    data class ArtistReleases(val listing: MusicListing, val artist: String, val singles: Boolean) : MusicPage {
        override val label: String
            get() = "all ${if (singles) "singles" else "albums"} of \"$artist\" ${listing.browseId}"
    }

    data class Playlist(val playlist: com.dewijones92.totum.innertube.playlists.Playlist) : MusicPage {
        override val label: String get() = "playlist ${playlist.browseId} \"${playlist.title}\""
    }
}

fun MediaItem.artistPage(): MusicPage.Artist? {
    if (contentKind != MediaContentKind.MUSIC) return null
    val channelId = sourceUrl?.youTubeChannelId
    val name = author ?: return channelId?.let { MusicPage.Artist(it, title) }
    return MusicPage.Artist(channelId, name)
}

fun MediaItem.albumPage(): MusicPage.Album? = album
    ?.takeIf { contentKind == MediaContentKind.MUSIC }
    ?.let { MusicPage.Album(it.id, title = it.title, artist = author) }
