package com.dewijones92.totum.playback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.dewijones92.totum.common.Diag

public enum class SkipDirection(internal val action: String) {
    PREVIOUS(ACTION_QUEUE_PREVIOUS),
    NEXT(ACTION_QUEUE_NEXT),
}

internal class QueueSkipButtons(private val context: Context) {

    fun handles(action: String): Boolean = action == ACTION_QUEUE_PREVIOUS || action == ACTION_QUEUE_NEXT

    fun onCommand(session: MediaSession, from: MediaSession.ControllerInfo, action: String) {
        val direction = if (action == ACTION_QUEUE_NEXT) SkipDirection.NEXT else SkipDirection.PREVIOUS
        tellTheApp(session, from, direction, "the notification")
    }

    fun onMediaButton(session: MediaSession, from: MediaSession.ControllerInfo, intent: Intent): Boolean {
        val key = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
        val direction = when (key.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> SkipDirection.NEXT
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> SkipDirection.PREVIOUS
            else -> return false
        }
        if (key.action == KeyEvent.ACTION_DOWN && key.repeatCount == 0) {
            tellTheApp(
                session,
                from,
                direction,
                "a media key (${from.packageName})"
            )
        }
        return true
    }

    @OptIn(UnstableApi::class)
    fun buttons(): List<CommandButton> = listOf(
        button(
            SkipDirection.PREVIOUS,
            CommandButton.ICON_PREVIOUS,
            R.string.playback_queue_previous,
            CommandButton.SLOT_BACK
        ),
        button(SkipDirection.NEXT, CommandButton.ICON_NEXT, R.string.playback_queue_next, CommandButton.SLOT_FORWARD),
    )

    @OptIn(UnstableApi::class)
    private fun button(direction: SkipDirection, icon: Int, name: Int, slot: Int): CommandButton =
        CommandButton.Builder(icon)
            .setDisplayName(context.getString(name))
            .setSessionCommand(SessionCommand(direction.action, Bundle.EMPTY))
            .setSlots(slot)
            .build()

    @OptIn(UnstableApi::class)
    private fun tellTheApp(
        session: MediaSession,
        from: MediaSession.ControllerInfo,
        direction: SkipDirection,
        source: String,
    ) {
        val apps = session.connectedControllers.filter {
            it.packageName == context.packageName && !session.isMediaNotificationController(it) && it != from
        }
        Diag.log(TAG, "queue $direction from $source; telling ${apps.size} app controller(s)")
        apps.forEach {
            session.sendCustomCommand(
                it,
                SessionCommand(ACTION_QUEUE_SKIP_REQUEST, Bundle.EMPTY),
                bundleOf(EXTRA_SKIP_DIRECTION to direction.name, EXTRA_SKIP_FROM to source),
            )
        }
    }

    companion object {
        private const val TAG = "queue"

        val notQueueAware: List<Int> = listOf(
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        )
    }
}

internal const val ACTION_QUEUE_PREVIOUS: String = "com.dewijones92.totum.QUEUE_PREVIOUS"
internal const val ACTION_QUEUE_NEXT: String = "com.dewijones92.totum.QUEUE_NEXT"
internal const val ACTION_QUEUE_SKIP_REQUEST: String = "com.dewijones92.totum.QUEUE_SKIP_REQUEST"
internal const val EXTRA_SKIP_DIRECTION: String = "skip_direction"
internal const val EXTRA_SKIP_FROM: String = "skip_from"
