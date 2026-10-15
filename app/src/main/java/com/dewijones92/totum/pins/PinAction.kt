package com.dewijones92.totum.pins

import androidx.annotation.StringRes
import com.dewijones92.totum.R

enum class PinAction(@param:StringRes val label: Int) {
    PLAY(R.string.pin_menu_play),
    SHUFFLE(R.string.pin_menu_shuffle),
    PLAY_NEXT(R.string.queue_play_next),
    ADD_TO_QUEUE(R.string.queue_add),
    START_RADIO(R.string.pin_menu_radio),
    GO_TO_ALBUM(R.string.pin_menu_go_album),
    GO_TO_ARTIST(R.string.pin_menu_go_artist),
    GO_TO_PLAYLIST(R.string.pin_menu_go_playlist),
    GO_TO_SOURCE(R.string.pin_menu_go_source),
    GO_TO_SEED(R.string.pin_menu_seed),
    SAVE_ALBUM(R.string.pin_menu_save),
    FOLLOW_ARTIST(R.string.pin_menu_follow),
    SHUFFLE_SONGS(R.string.pin_menu_shuffle_songs),
    ARTIST_RADIO(R.string.pin_menu_artist_radio),
    RESTART_RADIO(R.string.pin_menu_restart_radio),
    SHUFFLE_AGAIN(R.string.pin_menu_shuffle_again),
    TOGGLE_PIN(R.string.pin_pin),
    ADD_TO_HOME(R.string.pin_home),
    REMOVE_FROM_RECENT(R.string.pin_menu_forget),
}

fun pinMenuFor(pin: Pin, inRecent: Boolean): List<PinAction> {
    val own = when (pin) {
        is Pin.Item -> emptyList()
        is Pin.Album -> listOf(
            PinAction.PLAY,
            PinAction.SHUFFLE,
            PinAction.PLAY_NEXT,
            PinAction.ADD_TO_QUEUE,
            PinAction.START_RADIO,
            PinAction.GO_TO_ALBUM,
            PinAction.GO_TO_ARTIST,
            PinAction.SAVE_ALBUM,
        )
        is Pin.Playlist -> listOf(
            PinAction.PLAY,
            PinAction.SHUFFLE,
            PinAction.PLAY_NEXT,
            PinAction.ADD_TO_QUEUE,
            PinAction.GO_TO_PLAYLIST,
        )
        is Pin.Artist -> listOfNotNull(
            PinAction.SHUFFLE_SONGS.takeIf { pin.browseId != null },
            PinAction.ARTIST_RADIO.takeIf { pin.radioPlaylistId != null },
            PinAction.GO_TO_ARTIST,
            PinAction.FOLLOW_ARTIST.takeIf { pin.browseId != null },
        )
        is Pin.Radio -> listOfNotNull(PinAction.RESTART_RADIO, PinAction.GO_TO_SEED.takeIf { pin.videoId != null })
        is Pin.Show -> listOf(PinAction.PLAY, PinAction.GO_TO_SOURCE)
        is Pin.ShuffleAllOf -> listOfNotNull(
            PinAction.SHUFFLE_AGAIN,
            PinAction.GO_TO_SOURCE.takeIf { pin.sources.size == 1 },
        )
        is Pin.ArtistSongs -> listOf(PinAction.SHUFFLE_AGAIN, PinAction.GO_TO_ARTIST)
    }
    val common = listOfNotNull(
        PinAction.TOGGLE_PIN,
        PinAction.ADD_TO_HOME,
        PinAction.REMOVE_FROM_RECENT.takeIf { inRecent },
    )
    return own + common
}
