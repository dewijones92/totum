package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.settings.RepeatMode

sealed interface QueueNotice {
    data class RepeatOneOff(val groupTitle: String) : QueueNotice
    data class CarriedOn(val positionMs: Long) : QueueNotice
    data class AlreadyQueued(
        val item: PlayableItem,
        val position: Int,
        val playing: Boolean,
        val next: Boolean,
    ) : QueueNotice {
        val canMoveUp: Boolean get() = !playing && !next
    }
    data class SomeAlreadyQueued(val count: Int) : QueueNotice
}

class RepeatOneRule(
    private val chosen: () -> RepeatMode,
    private val kidLocked: () -> Boolean,
    private val setMode: (RepeatMode) -> Unit,
) {
    fun switchOffForRunOf(groupTitle: String): Boolean {
        val mode = chosen()
        return when {
            mode != RepeatMode.ONE -> false.also {
                Diag.log("queue", "run of \"$groupTitle\" starts: repeat is $mode, left as it is")
            }
            kidLocked() -> false.also {
                Diag.log("queue", "run of \"$groupTitle\" starts: repeat one is chosen but kid lock sets repeat itself")
            }
            else -> true.also {
                Diag.log("queue", "run of \"$groupTitle\" starts: repeat one was on, switched off so the run moves on")
                setMode(RepeatMode.OFF)
            }
        }
    }
}
