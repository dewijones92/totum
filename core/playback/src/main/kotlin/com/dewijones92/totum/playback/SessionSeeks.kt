package com.dewijones92.totum.playback

import androidx.media3.common.Player

internal object SessionSeeks {
    fun named(command: Int): String? = when (command) {
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM -> "a seek"
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION -> "a seek to the start"
        Player.COMMAND_SEEK_TO_PREVIOUS -> "previous"
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> "previous item"
        Player.COMMAND_SEEK_TO_NEXT -> "next"
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> "next item"
        Player.COMMAND_SEEK_TO_MEDIA_ITEM -> "a jump to an item"
        Player.COMMAND_SEEK_BACK -> "seek back"
        Player.COMMAND_SEEK_FORWARD -> "seek forward"
        else -> null
    }
}
