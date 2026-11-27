package com.dewijones92.totum.playback

import android.app.Notification
import android.content.Context
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.dewijones92.totum.common.Diag
import com.google.common.collect.ImmutableList

@UnstableApi
internal class RemoteTintedNotificationProvider(
    private val context: Context,
    private val delegate: MediaNotification.Provider,
) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        if (mediaSession.id != RemoteSessionController.REMOTE_SESSION_ID) {
            return delegate.createNotification(
                mediaSession,
                mediaButtonPreferences,
                actionFactory,
                onNotificationChangedCallback,
            )
        }
        val made = delegate.createNotification(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
            MediaNotification.Provider.Callback { late ->
                onNotificationChangedCallback.onNotificationChanged(tinted(mediaSession, late))
            },
        )
        return tinted(mediaSession, made)
    }

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    private fun tinted(session: MediaSession, made: MediaNotification): MediaNotification {
        val extras = session.player.mediaMetadata.extras
        val label = extras?.getString(EXTRA_REMOTE_DEVICE)
        if (extras == null || label == null || !extras.containsKey(EXTRA_REMOTE_COLOUR)) {
            Diag.log(TAG, "dewidebug the second session's notification has no device on its item yet; left as built")
            return made
        }
        val colour = extras.getLong(EXTRA_REMOTE_COLOUR).toInt()
        return try {
            val rebuilt = Notification.Builder.recoverBuilder(context, made.notification)
                .setColor(colour)
                .setSubText(label)
                .build()
            Diag.log(TAG, "dewidebug notification for $label tinted ${colour.toUInt().toString(HEX)}")
            MediaNotification(made.notificationId, rebuilt)
        } catch (failure: IllegalArgumentException) {
            Diag.warn(TAG, "dewidebug could not tint the notification for $label; left as built", failure)
            made
        }
    }

    private companion object {
        const val TAG = "remote-session"
        const val HEX = 16
    }
}
