package com.dewijones92.totum.playback

import androidx.media3.common.C
import androidx.media3.common.Player

internal data class RemoteView(
    val items: List<RemoteItem>,
    val currentIndex: Int,
    val playWhenReady: Boolean,
    val playbackState: Int,
    val durationMs: Long?,
    val positionMs: Long,
    val seekable: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
) {
    val commands: List<Int>
        get() = buildList {
            add(Player.COMMAND_PLAY_PAUSE)
            add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            add(Player.COMMAND_GET_METADATA)
            add(Player.COMMAND_GET_TIMELINE)
            add(Player.COMMAND_RELEASE)
            if (seekable) {
                add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                add(Player.COMMAND_SEEK_BACK)
                add(Player.COMMAND_SEEK_FORWARD)
            }
            if (hasNext) {
                add(Player.COMMAND_SEEK_TO_NEXT)
                add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            }
            if (hasPrevious) {
                add(Player.COMMAND_SEEK_TO_PREVIOUS)
                add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            }
        }

    val durationUs: Long get() = durationMs?.let { it * MICROS_PER_MILLI } ?: C.TIME_UNSET

    companion object {
        val EMPTY = RemoteView(
            items = emptyList(),
            currentIndex = C.INDEX_UNSET,
            playWhenReady = false,
            playbackState = Player.STATE_IDLE,
            durationMs = null,
            positionMs = 0,
            seekable = false,
            hasPrevious = false,
            hasNext = false,
        )

        fun of(facts: RemoteFacts?): RemoteView {
            if (facts == null) return EMPTY
            val length = facts.durationMs?.takeIf { it > 0 }
            return RemoteView(
                items = listOfNotNull(facts.previous, facts.current, facts.next),
                currentIndex = if (facts.previous != null) 1 else 0,
                playWhenReady = facts.playing || facts.starting,
                playbackState = if (facts.starting) Player.STATE_BUFFERING else Player.STATE_READY,
                durationMs = length,
                positionMs = facts.positionMs.coerceAtLeast(0),
                seekable = length != null && !facts.starting,
                hasPrevious = facts.previous != null,
                hasNext = facts.next != null,
            )
        }

        private const val MICROS_PER_MILLI = 1_000L
    }
}

internal object RemoteSeeks {

    fun commandFor(index: Int, currentIndex: Int, positionMs: Long, durationMs: Long?): RemoteCommand = when {
        index > currentIndex -> RemoteCommand.Next
        index < currentIndex -> RemoteCommand.Previous
        else -> RemoteCommand.SeekTo(positionMs.coerceIn(0, durationMs ?: Long.MAX_VALUE))
    }
}

internal sealed interface RemoteChange {
    val line: String

    data class Started(val to: RemoteFacts) : RemoteChange {
        override val line get() = "started for ${to.deviceLabel}: ${to.current.title}"
    }

    data class SwitchedDevice(val from: RemoteFacts, val to: RemoteFacts) : RemoteChange {
        override val line get() = "moved from ${from.deviceLabel} to ${to.deviceLabel}"
    }

    data class NewItem(val to: RemoteFacts) : RemoteChange {
        override val line get() = "${to.deviceLabel} moved on to ${to.current.title}"
    }

    data class Updated(val to: RemoteFacts) : RemoteChange {
        override val line get() = "${to.deviceLabel} updated: playing=${to.playing} starting=${to.starting}"
    }

    data class Ended(val from: RemoteFacts) : RemoteChange {
        override val line get() = "ended: ${from.deviceLabel} has nothing playing or queued to show"
    }

    data object NothingToShow : RemoteChange {
        override val line get() = "nothing to show"
    }
}

internal object RemoteSessionPlan {

    fun decide(before: RemoteFacts?, after: RemoteFacts?): RemoteChange = when {
        before == null && after == null -> RemoteChange.NothingToShow
        before == null -> RemoteChange.Started(checkNotNull(after))
        after == null -> RemoteChange.Ended(before)
        before.deviceId != after.deviceId -> RemoteChange.SwitchedDevice(before, after)
        before.current.id != after.current.id || before.current.queueIndex != after.current.queueIndex ->
            RemoteChange.NewItem(after)
        else -> RemoteChange.Updated(after)
    }
}
