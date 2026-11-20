package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId

internal class NoTracksWatch(
    private val player: () -> Player?,
    private val later: (delayMs: Long, action: () -> Unit) -> Unit,
    private val report: (PlaybackEvent.CouldNotPlay) -> Unit,
) : Player.Listener {

    private var reportedFor: String? = null

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) suspect("ready")
    }

    override fun onTracksChanged(tracks: Tracks) {
        if (player()?.playbackState == Player.STATE_READY) suspect("tracks changed")
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        reportedFor = null
    }

    private fun suspect(why: String) {
        val current = player() ?: return
        val id = current.currentMediaItem?.mediaId?.ifBlank { null } ?: return
        if (!current.currentTracks.isEmpty || current.isOnAReceiver() || reportedFor == id) return
        val from = current.currentPosition
        later(RECHECK_MS) { confirm(id, why, from) }
    }

    private fun confirm(id: String, why: String, positionAtSuspicion: Long) {
        val current = player() ?: return
        val stillIt = current.currentMediaItem?.mediaId == id && current.playbackState == Player.STATE_READY
        if (!stillIt || !current.currentTracks.isEmpty || reportedFor == id) return
        val advancedMs = current.currentPosition - positionAtSuspicion
        val playing = current.isPlaying && advancedMs >= ADVANCING_MS
        val commands = runCatching { current.availableCommands.commandsOf() }.getOrElse { "unreadable" }
        val verdict = if (playing) "it is playing, so this is not told to the user" else "told to the user"
        Diag.log(
            "playback",
            "dewidebug no-tracks check on $id ($why): the controller sees isPlaying=${current.isPlaying} " +
                "playWhenReady=${current.playWhenReady} position ${positionAtSuspicion}ms -> " +
                "${current.currentPosition}ms (advanced ${advancedMs}ms) tracks=${current.currentTracks.groups.size} " +
                "getTracksAvailable=${current.isCommandAvailable(Player.COMMAND_GET_TRACKS)} " +
                "availableCommands=$commands -> $verdict",
        )
        if (playing) return
        reportedFor = id
        val summary = TrackSummary.of(emptyList(), current.isCurrentMediaItemSeekable)
        Diag.warn(
            "playback",
            "$id still has no tracks the player can use ${RECHECK_MS}ms after it was ready ($why) at " +
                "${current.currentPosition}ms: $summary; telling the user its track list is probably at the end " +
                "of the file",
        )
        report(
            PlaybackEvent.CouldNotPlay(
                MediaItemId(id),
                current.currentPosition,
                PlaybackProblem.NoTrackList(summary),
                hasSound = current.currentTracks.containsType(C.TRACK_TYPE_AUDIO),
            ),
        )
    }

    private fun Player.Commands.commandsOf(): List<Int> = List(size()) { get(it) }

    private companion object {
        const val RECHECK_MS = 1_500L
        const val ADVANCING_MS = 500L
    }
}
