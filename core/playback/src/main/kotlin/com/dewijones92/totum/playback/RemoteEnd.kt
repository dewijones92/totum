package com.dewijones92.totum.playback

import android.os.SystemClock
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId

internal class RemoteEnd : Player.Listener {

    var onEnded: (PlaybackEvent.Ended) -> Unit = {}

    private data class Seen(
        val id: String,
        val positionMs: Long,
        val durationMs: Long,
        val playing: Boolean,
        val atMs: Long,
    )

    private var seen: Seen? = null

    private fun observe(player: Player) {
        val item = player.currentMediaItem
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE) {
            seen = null
            return
        }
        if (item == null || item.mediaId.isBlank()) return
        val duration = player.duration.takeIf { it > 0 } ?: return
        seen = Seen(item.mediaId, player.currentPosition, duration, player.isPlaying, SystemClock.elapsedRealtime())
    }

    fun finishedOnTv(from: String, to: String?): Boolean? {
        val last = seen?.takeIf { it.id == from } ?: return null
        val reached = last.positionMs + if (last.playing) SystemClock.elapsedRealtime() - last.atMs else 0
        val ended = reached >= last.durationMs - NEAR_END_MS
        Diag.log(
            "cast",
            "$from -> $to on the TV at ~${reached}ms: ${if (ended) "it ended" else "skipped"} by Totum's own clock"
        )
        return ended
    }

    private var player: Player? = null

    override fun onEvents(player: Player, events: Player.Events) {
        this.player = player
        observe(player)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        player?.let { vanished(it, mediaItem) }?.let(onEnded)
    }

    private fun vanished(player: Player, now: MediaItem?): PlaybackEvent.Ended? {
        val last = seen ?: return null
        if (now != null && now.mediaId == last.id) return null
        seen = null
        if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE || now != null) return null
        val since = SystemClock.elapsedRealtime() - last.atMs
        val reached = last.positionMs + if (last.playing) since else 0
        val ended = last.playing && reached >= last.durationMs - NEAR_END_MS
        Diag.log(
            "cast",
            "${last.id} left the receiver at ~${reached}ms of ${last.durationMs}ms " +
                "(last seen ${last.positionMs}ms, ${since}ms ago, playing=${last.playing}) -> " +
                if (ended) "it ended there; the queue moves on" else "not an end; nothing advances",
        )
        return if (ended) PlaybackEvent.Ended(MediaItemId(last.id), reached, last.durationMs) else null
    }

    private companion object {
        const val NEAR_END_MS = 5_000L
    }
}
