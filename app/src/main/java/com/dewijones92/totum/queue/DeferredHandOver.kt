package com.dewijones92.totum.queue

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackState
import kotlin.math.abs

internal class DeferredHandOver {

    data class Pending(val id: MediaItemId, val deferredAtMs: Long, val lastSeenMs: Long)

    sealed interface Verdict {
        data object Wait : Verdict
        data class SwapNow(val pending: Pending, val trigger: String, val positionMs: Long) : Verdict
        data class Drop(val pending: Pending, val reason: String) : Verdict
    }

    private var pending: Pending? = null

    fun defer(id: MediaItemId, positionMs: Long) {
        pending = Pending(id, deferredAtMs = positionMs, lastSeenMs = positionMs)
    }

    fun drop(): Pending? = pending.also { pending = null }

    fun judge(state: PlaybackState?): Verdict {
        val waiting = pending ?: return Verdict.Wait
        if (state == null) return Verdict.Wait
        if (state.itemId != waiting.id) return dropped(waiting, "${state.itemId.value} is playing instead")
        if (state.hasEnded) return dropped(waiting, "it has ended")
        val jumped = abs(state.positionMs - waiting.lastSeenMs) >= SEEK_JUMP_MS
        val trigger = when {
            !state.wantsToPlay -> "it was paused"
            state.isBuffering -> "the stream stalled"
            jumped -> "the position jumped from ${waiting.lastSeenMs}ms (a seek or a skip)"
            else -> null
        }
        if (trigger == null) {
            pending = waiting.copy(lastSeenMs = state.positionMs)
            return Verdict.Wait
        }
        pending = null
        return Verdict.SwapNow(waiting, trigger, state.positionMs)
    }

    private fun dropped(waiting: Pending, reason: String): Verdict {
        pending = null
        return Verdict.Drop(waiting, reason)
    }

    companion object {
        const val SEEK_JUMP_MS = 3_000L

        fun isHealthy(state: PlaybackState): Boolean =
            state.wantsToPlay && state.isPlaying && !state.isBuffering && !state.hasEnded
    }
}
