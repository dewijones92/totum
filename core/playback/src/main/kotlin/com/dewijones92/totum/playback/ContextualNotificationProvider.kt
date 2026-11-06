package com.dewijones92.totum.playback

import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import com.dewijones92.totum.common.Diag

@UnstableApi
internal class ContextualNotificationProvider(
    private val context: Context,
    private val refresh: () -> Unit,
) : DefaultMediaNotificationProvider(context) {

    @Volatile
    private var playbackContext = PlaybackContext()

    fun handles(action: String): Boolean = action == ACTION_PLAYBACK_CONTEXT

    fun onCommand(args: Bundle) {
        val next = PlaybackContext.from(args)
        if (next == playbackContext) return
        Diag.log("playback", "dewidebug notification context $playbackContext -> $next; refreshing the notification")
        playbackContext = next
        refresh()
    }

    override fun getNotificationContentText(metadata: MediaMetadata): CharSequence? {
        val artist = super.getNotificationContentText(metadata)
        if (!playbackContext.repeatOne) return artist
        val badge = context.getString(R.string.playback_notification_repeat_one)
        return if (artist.isNullOrBlank()) badge else "$badge · $artist"
    }
}
