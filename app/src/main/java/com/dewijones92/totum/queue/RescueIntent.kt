package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class RescueIntent(private val controller: PlaybackController) {
    private val guards = mutableMapOf<MediaItemId, Int>()
    private val overridden = mutableSetOf<MediaItemId>()

    fun freshPlay(id: MediaItemId) {
        val rescuing = synchronized(guards) { (id in guards).also { if (it) overridden += id } }
        if (!rescuing) return
        Diag.log("playback", "${id.value} was asked to play afresh during its rescue; the rescue's hold is dropped")
        controller.holdPausedForNextPlay(null)
    }

    suspend fun <T> keeping(id: MediaItemId, rescue: String, block: suspend () -> T): T {
        synchronized(guards) { guards[id] = (guards[id] ?: 0) + 1 }
        var holding: Boolean? = null
        fun observe(state: PlaybackState?) {
            if (state?.itemId != id) return
            val wanting = state.wantsToPlay
            val overriding = synchronized(guards) {
                if (wanting) overridden.remove(id)
                id in overridden
            }
            val hold = !wanting && !overriding
            controller.holdPausedForNextPlay(if (hold) id else null)
            if (hold == holding) return
            holding = hold
            Diag.log(
                "playback",
                if (hold) {
                    "${id.value} is paused during its $rescue; whatever the rescue plays will start paused"
                } else {
                    "${id.value} wants to play during its $rescue; the rescue will play it"
                },
            )
        }
        observe(controller.state.value)
        return coroutineScope {
            val watcher = launch { controller.state.collect(::observe) }
            try {
                block()
            } finally {
                watcher.cancel()
                if (release(id)) controller.holdPausedForNextPlay(null)
            }
        }
    }

    private fun release(id: MediaItemId): Boolean = synchronized(guards) {
        val left = (guards[id] ?: 1) - 1
        if (left <= 0) {
            guards.remove(id)
            overridden.remove(id)
        } else {
            guards[id] = left
        }
        left <= 0
    }
}
