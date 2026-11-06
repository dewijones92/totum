package com.dewijones92.totum.kidlock

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.settings.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

object KidRules {
    fun repeat(locked: Boolean, playingGroup: String?, chosen: RepeatMode): RepeatMode = when {
        !locked -> chosen
        playingGroup != null -> RepeatMode.GROUP
        else -> RepeatMode.ONE
    }

    fun autoPlay(locked: Boolean, chosen: Boolean): Boolean = locked || chosen

    fun explores(locked: Boolean): Boolean = !locked

    const val SPEED = 1f
}

class KidMode(
    private val lock: KidLock,
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
) {
    private var before: Pair<Float, Boolean>? = null

    val locked: Boolean get() = lock.locked.value

    fun start() {
        scope.launch {
            combine(lock.locked, controller.state) { locked, state -> locked to state }
                .distinctUntilChanged()
                .collect { (locked, state) ->
                    if (locked) hold(state?.speed, state?.skipSilence) else release()
                }
        }
    }

    private fun hold(speed: Float?, skipSilence: Boolean?) {
        if (before == null) {
            before = (speed ?: KidRules.SPEED) to (skipSilence ?: false)
            Diag.log(
                "kidlock",
                "kid mode: speed 1x and no silence skipping until unlocked, none of it saved as a choice " +
                    "(was ${before?.first}x, skip=${before?.second})"
            )
        }
        if (speed != null && speed != KidRules.SPEED) {
            Diag.log("kidlock", "kid mode: speed was ${speed}x; back to 1x")
            controller.setSpeedForNow(KidRules.SPEED)
        }
        if (skipSilence == true) {
            Diag.log("kidlock", "kid mode: silence skipping came on; off again")
            controller.setSkipSilenceForNow(false)
        }
    }

    private fun release() {
        val (speed, skip) = before ?: return
        before = null
        Diag.log("kidlock", "kid mode over: speed ${speed}x and skip=$skip again")
        controller.setSpeedForNow(speed)
        controller.setSkipSilenceForNow(skip)
    }
}
