package com.dewijones92.totum.ui.common

import androidx.annotation.StringRes
import com.dewijones92.totum.R
import com.dewijones92.totum.pins.Pin

@StringRes
fun Pin.kindWords(): Int = when (this) {
    is Pin.Item -> playable()?.let { itemKindWords(shownAs(it.item, it.handle.pillar)) } ?: R.string.pin_kind_item
    is Pin.Album -> if (shuffled) R.string.pin_kind_album_shuffled else R.string.pin_kind_album
    is Pin.Playlist -> R.string.pin_kind_playlist
    is Pin.Artist -> R.string.pin_kind_artist
    is Pin.Radio -> R.string.pin_kind_radio
    is Pin.Show -> R.string.pin_kind_show
    is Pin.ShuffleAllOf -> R.string.pin_kind_shuffle_all
    is Pin.ArtistSongs -> R.string.pin_kind_artist_songs
}

@StringRes
private fun itemKindWords(shown: Shown): Int = when (shown) {
    Shown.VIDEO -> R.string.pin_kind_video
    Shown.PODCAST -> R.string.pin_kind_episode
    Shown.TORRENT -> R.string.pin_kind_torrent
    Shown.MUSIC -> R.string.pin_kind_song
}
