package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId

public class PlaybackInterruption(private val controller: PlaybackController, private val tag: String) {

    public sealed interface Released {
        public data class Resumed(val itemId: MediaItemId) : Released
        public data class LeftAlone(val reason: String) : Released
    }

    private var held: MediaItemId? = null
    private var supersededBy: String? = null

    public val holding: Boolean get() = held != null

    public fun interrupt(): Boolean {
        val state = controller.state.value
        if (state == null || !state.wantsToPlay) {
            val seen = state?.let { "wantsToPlay=false item=${it.itemId.value}" } ?: "nothing queued"
            Diag.log(tag, "dewidebug interrupt: nothing to pause ($seen)")
            return false
        }
        held = state.itemId
        supersededBy = null
        controller.setPlaying(false)
        Diag.log(tag, "dewidebug interrupt: paused item=${state.itemId.value} at ${state.positionMs}ms")
        return true
    }

    public fun observe(state: PlaybackState?) {
        val item = held ?: return
        val reason = when {
            state == null -> "playback ended"
            state.itemId != item -> "item changed to ${state.itemId.value}"
            state.wantsToPlay -> "resumed by hand"
            else -> null
        } ?: return
        held = null
        supersededBy = reason
        Diag.log(tag, "dewidebug interrupt: no longer holding item=${item.value} ($reason)")
    }

    public fun release(): Released {
        val item = held
        held = null
        val outcome = when {
            item == null -> Released.LeftAlone(supersededBy?.let { "superseded: $it" } ?: "nothing was paused")
            controller.state.value?.itemId != item -> Released.LeftAlone("item changed before release")
            else -> {
                controller.setPlaying(true)
                Released.Resumed(item)
            }
        }
        supersededBy = null
        Diag.log(tag, "dewidebug interrupt release: $outcome")
        return outcome
    }
}
