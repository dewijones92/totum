package com.dewijones92.totum.playback

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.dewijones92.totum.common.Diag
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture

@UnstableApi
internal class RemoteTargetPlayer(
    looper: Looper,
    private val later: (delayMs: Long, action: () -> Unit) -> Unit,
) : SimpleBasePlayer(looper) {

    private var facts: RemoteFacts? = null
    private var view = RemoteView.EMPTY
    private var position: PositionSupplier = PositionSupplier.ZERO
    private var settling: SettableFuture<Any?>? = null

    fun show(next: RemoteFacts?) {
        facts = next
        view = RemoteView.of(next)
        position = if (next?.playing == true && !next.starting) {
            PositionSupplier.getExtrapolating(view.positionMs, 1f)
        } else {
            PositionSupplier.getConstant(view.positionMs)
        }
        invalidateState()
        settling?.set(null)
        settling = null
    }

    override fun getState(): State {
        val shown = view
        val builder = State.Builder()
            .setAvailableCommands(Player.Commands.Builder().apply { shown.commands.forEach(::add) }.build())
            .setPlayWhenReady(shown.playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)
            .setPlaybackState(shown.playbackState)
            .setPlaylist(shown.items.mapIndexed { index, item -> itemData(item, index == shown.currentIndex) })
            .setContentPositionMs(position)
            .setSeekBackIncrementMs(SeekSteps.BACK_MS)
            .setSeekForwardIncrementMs(SeekSteps.FORWARD_MS)
        if (shown.currentIndex >= 0) builder.setCurrentMediaItemIndex(shown.currentIndex)
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> =
        asked(if (playWhenReady) RemoteCommand.Play else RemoteCommand.Pause)

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> =
        asked(RemoteSeeks.commandFor(mediaItemIndex, view.currentIndex, positionMs, view.durationMs))

    override fun handleRelease(): ListenableFuture<*> = Futures.immediateVoidFuture()

    private fun asked(command: RemoteCommand): ListenableFuture<*> {
        Diag.log(
            TAG,
            "dewidebug $command sent to ${facts?.deviceLabel} (shown position ${view.positionMs}ms, " +
                "playing=${facts?.playing}, starting=${facts?.starting}); holding the shown state until its answer " +
                "or ${SETTLE_MS}ms",
        )
        RemoteSessionHost.commands.send(command)
        settling?.set(null)
        val pending = SettableFuture.create<Any?>()
        settling = pending
        later(SETTLE_MS) {
            if (settling === pending) {
                Diag.log(TAG, "dewidebug no answer within ${SETTLE_MS}ms for $command; showing what was last read")
                settling = null
                pending.set(null)
            }
        }
        return pending
    }

    private fun itemData(item: RemoteItem, current: Boolean): MediaItemData {
        val metadata = metadataOf(item, current)
        return MediaItemData.Builder("${item.queueIndex}:${item.id}")
            .setMediaItem(MediaItem.Builder().setMediaId(item.id).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setDurationUs(if (current) view.durationUs else C.TIME_UNSET)
            .setIsSeekable(current && view.seekable)
            .build()
    }

    private fun metadataOf(item: RemoteItem, current: Boolean): MediaMetadata {
        val shown = facts
        val builder = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(if (current) listOfNotNull(shown?.statusLine, item.author).joinToString(" · ") else item.author)
            .setArtworkUri(item.artworkUrl?.let(Uri::parse))
        if (current && shown != null) {
            builder.setExtras(
                Bundle().apply {
                    putString(EXTRA_REMOTE_DEVICE, shown.deviceLabel)
                    putLong(EXTRA_REMOTE_COLOUR, shown.colour)
                },
            )
        }
        return builder.build()
    }

    internal companion object {
        const val TAG = "remote-session"
        const val SETTLE_MS = 2_500L
    }
}

internal const val EXTRA_REMOTE_DEVICE: String = "com.dewijones92.totum.REMOTE_DEVICE"
internal const val EXTRA_REMOTE_COLOUR: String = "com.dewijones92.totum.REMOTE_COLOUR"
