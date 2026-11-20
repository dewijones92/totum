package com.dewijones92.totum.playback

import android.app.PendingIntent
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.dewijones92.totum.common.Diag
import com.google.common.collect.ImmutableList

@UnstableApi
internal class RemoteSessionController(
    private val service: MediaSessionService,
    private val bitmapLoader: () -> BitmapLoader,
    private val openApp: () -> PendingIntent?,
    private val playingChanged: (Boolean) -> Unit,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {

    private var player: RemoteTargetPlayer? = null

    var session: MediaSession? = null
        private set

    private var facts: RemoteFacts? = null

    fun start() {
        val already = RemoteSessionHost.attach { next -> main.post { show(next, "published") } }
        Diag.log(
            TAG,
            "dewidebug the lock screen and notification session is listening; already shown: ${already?.deviceLabel}",
        )
        if (already != null) show(already, "already there when the service started")
    }

    fun stop() {
        RemoteSessionHost.detach()
        close("the playback service is shutting down")
    }

    fun show(next: RemoteFacts?, source: String) {
        val change = RemoteSessionPlan.decide(facts, next)
        Diag.log(
            TAG,
            "dewidebug $source: ${change.line} [session open=${session != null} " +
                "playing=${next?.playing} starting=${next?.starting} position=${next?.positionMs}ms " +
                "length=${next?.durationMs}ms previous=${next?.previous?.id} next=${next?.next?.id}]",
        )
        facts = next
        when (change) {
            RemoteChange.NothingToShow -> Unit
            is RemoteChange.Ended -> close(change.line)
            else -> {
                open(change.line)
                player?.show(next)
            }
        }
    }

    private fun open(why: String) {
        if (session != null) return
        val remote = RemoteTargetPlayer(Looper.getMainLooper()) { delayMs, action ->
            main.postDelayed({ action() }, delayMs)
        }
        remote.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    Diag.log(TAG, "dewidebug the selected device is ${if (isPlaying) "playing" else "not playing"}")
                    playingChanged(isPlaying)
                }
            },
        )
        val built = MediaSession.Builder(service, remote)
            .setId(REMOTE_SESSION_ID)
            .setBitmapLoader(bitmapLoader())
            .setCallback(RemoteSessionCallback())
            .setMediaButtonPreferences(buttons(service))
            .apply { openApp()?.let { setSessionActivity(it) } }
            .build()
        service.addSession(built)
        player = remote
        session = built
        Diag.log(TAG, "dewidebug opened the second session beside the phone's, because $why")
    }

    private fun close(why: String) {
        val closing = session ?: return
        service.removeSession(closing)
        closing.release()
        player?.release()
        session = null
        player = null
        Diag.log(TAG, "dewidebug closed the second session: $why")
    }

    private class RemoteSessionCallback : MediaSession.Callback {
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            playerCommand: Int,
        ): Int {
            val who = when {
                session.isMediaNotificationController(controller) -> "the notification or lock screen"
                else -> controller.packageName
            }
            val what = SessionSeeks.named(playerCommand) ?: SessionSeeks.playPause(playerCommand)
            if (what != null) Diag.log(TAG, "dewidebug $what asked by $who for the selected device")
            return SessionResult.RESULT_SUCCESS
        }
    }

    companion object {
        const val REMOTE_SESSION_ID = "totum-remote-target"
        private const val TAG = "remote-session"

        fun buttons(context: Context): ImmutableList<CommandButton> = ImmutableList.of(
            CommandButton.Builder(CommandButton.ICON_SKIP_BACK_10)
                .setDisplayName(context.getString(R.string.playback_remote_back))
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
                .setDisplayName(context.getString(R.string.playback_remote_forward))
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName(context.getString(R.string.playback_remote_next))
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setSlots(CommandButton.SLOT_FORWARD_SECONDARY)
                .build(),
        )
    }
}
