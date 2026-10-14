package com.dewijones92.totum.playback

import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.os.bundleOf
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.Listening
import com.google.common.collect.ImmutableList

internal class ListeningSwitchButton(private val context: Context) {

    fun handles(action: String): Boolean = action == ACTION_SWITCH_BUTTON || action == ACTION_SWITCH_LISTENING

    @OptIn(UnstableApi::class)
    fun onCommand(session: MediaSession, from: MediaSession.ControllerInfo, action: String, args: Bundle) {
        if (action == ACTION_SWITCH_BUTTON) {
            val to = args.getString(EXTRA_SWITCH_TO)?.let { name -> Listening.entries.firstOrNull { it.name == name } }
            session.setMediaButtonPreferences(to?.let { ImmutableList.of(button(it)) } ?: ImmutableList.of())
            Diag.log(TAG, "switch button -> ${to ?: "hidden"}")
            return
        }
        val apps = session.connectedControllers.filter {
            it.packageName == context.packageName && !session.isMediaNotificationController(it) && it != from
        }
        Diag.log(TAG, "switch tapped by ${from.packageName}; telling ${apps.size} app controller(s)")
        apps.forEach {
            session.sendCustomCommand(
                it,
                SessionCommand(ACTION_SWITCH_LISTENING, Bundle.EMPTY),
                bundleOf(EXTRA_SWITCH_FROM to "notification")
            )
        }
    }

    @OptIn(UnstableApi::class)
    private fun button(to: Listening): CommandButton =
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(
                if (to == Listening.MUSIC) R.drawable.ic_switch_to_music else R.drawable.ic_switch_to_talk
            )
            .setDisplayName(
                context.getString(
                    if (to == Listening.MUSIC) R.string.playback_switch_to_music else R.string.playback_switch_to_talk
                ),
            )
            .setSessionCommand(SessionCommand(ACTION_SWITCH_LISTENING, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()

    private companion object {
        const val TAG = "switch"
    }
}

internal const val ACTION_SWITCH_BUTTON: String = "com.dewijones92.totum.SWITCH_BUTTON"
internal const val ACTION_SWITCH_LISTENING: String = "com.dewijones92.totum.SWITCH_LISTENING"
internal const val EXTRA_SWITCH_TO: String = "switch_to"
internal const val EXTRA_SWITCH_FROM: String = "switch_from"
