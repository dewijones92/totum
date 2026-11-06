package com.dewijones92.totum.playback

import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

internal class RemoteEnd(
    private val standIn: (Player) -> String?,
    clock: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    var onEnded: (PlaybackEvent.Ended) -> Unit = {}

    private val watch = RemoteEndWatch(clock)
    private var player: Player? = null

    private fun observe(player: Player) {
        val item = player.currentMediaItem
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            watch.forget()
            return
        }
        item ?: return
        val duration = player.duration.takeIf { it > 0 }
        val named = item.mediaId.ifBlank { null } ?: standIn(player)
        if (named == null) {
            watch.refresh(player.currentPosition, duration, player.isPlaying)
            return
        }
        watch.observe(named, player.currentPosition, duration, player.isPlaying)
    }

    fun finishedOnTv(from: String, to: String?): Boolean? = watch.finishedOnTv(from, to)

    override fun onEvents(player: Player, events: Player.Events) {
        this.player = player
        observe(player)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val current = player ?: return
        if (current.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            watch.forget()
            return
        }
        val nowId = mediaItem?.mediaId?.ifBlank { null } ?: mediaItem?.let { standIn(current) }
        watch.left(nowId, receiverStillHasAnItem = mediaItem != null)?.let(onEnded)
    }
}
