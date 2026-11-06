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
    private val currentItemId: () -> String?,
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
        val line = lineFor(playbackContext) ?: return artist
        return if (artist.isNullOrBlank()) line else "$line · $artist"
    }

    private fun lineFor(shown: PlaybackContext): String? {
        if (shown.repeatOne) return context.getString(R.string.playback_notification_repeat_one)
        val wantsNext = shown.explorePicksNext || shown.nextTitle != null
        if (!wantsNext) return null
        val playing = currentItemId()
        if (shown.nextAfter != playing) {
            Diag.log(
                "playback",
                "dewidebug notification next line held back: it follows ${shown.nextAfter}, playing $playing",
            )
            return null
        }
        return if (shown.explorePicksNext) {
            context.getString(R.string.playback_notification_explore_next)
        } else {
            context.getString(R.string.playback_notification_next, shown.nextTitle)
        }
    }
}
