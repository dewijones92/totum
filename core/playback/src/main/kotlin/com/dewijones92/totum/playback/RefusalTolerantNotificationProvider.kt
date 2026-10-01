package com.dewijones92.totum.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Bundle
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.dewijones92.totum.common.Diag
import com.google.common.collect.ImmutableList

@UnstableApi
internal class RefusalTolerantNotificationProvider(
    private val delegate: MediaNotification.Provider,
) : MediaNotification.Provider {

    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = delegate.createNotification(
        mediaSession,
        mediaButtonPreferences,
        actionFactory,
        tolerant(onNotificationChangedCallback),
    )

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        delegate.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        delegate.notificationChannelInfo

    companion object {
        fun tolerant(callback: MediaNotification.Provider.Callback): MediaNotification.Provider.Callback =
            MediaNotification.Provider.Callback { notification ->
                try {
                    callback.onNotificationChanged(notification)
                } catch (refused: ForegroundServiceStartNotAllowedException) {
                    Diag.warn(
                        "playback",
                        "dewidebug a late notification update needed a foreground start the system refused; " +
                            "keeping the previous notification",
                        refused,
                    )
                }
            }
    }
}
