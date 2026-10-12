package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.playback.PlaybackController

internal class QueueModeSwitch(
    private val controller: PlaybackController,
    private val playing: () -> PlayableItem?,
    private val playNow: suspend (PlayableItem) -> Boolean,
    private val reroute: suspend (PlayableItem, Long, Boolean) -> Boolean,
) {
    suspend fun play(item: PlayableItem): Boolean =
        if (playing()?.item?.id == item.item.id) current(item.item.id) else playNow(item)

    suspend fun current(expected: MediaItemId, forceVideo: Boolean = false): Boolean {
        val item = playing()?.takeIf { it.item.id == expected } ?: run {
            Diag.log("playback", "mode switch for ${expected.value} ignored: a different item is now playing")
            return false
        }
        val state = controller.state.value?.takeIf { it.itemId == expected }
        val position = state?.takeUnless { it.hasEnded }?.positionMs ?: 0L
        controller.disarmNext("mode changed for ${expected.value}")
        Diag.log("playback", "switching ${expected.value} in place at ${position}ms (forceVideo=$forceVideo)")
        return reroute(item, position, forceVideo)
    }
}
