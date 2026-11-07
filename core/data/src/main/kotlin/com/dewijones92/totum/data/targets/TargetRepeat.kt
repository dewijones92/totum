package com.dewijones92.totum.data.targets

import com.dewijones92.totum.domain.RepeatMode

public val RepeatMode.onATarget: Boolean
    get() = this == RepeatMode.OFF || this == RepeatMode.ONE || this == RepeatMode.QUEUE

public fun RepeatMode.nextAfterEnd(index: Int, size: Int, failed: Boolean, playedLongEnough: Boolean): Int {
    val repeats = !failed && playedLongEnough
    return when {
        repeats && this == RepeatMode.ONE -> index
        repeats && this == RepeatMode.QUEUE && index + 1 >= size -> 0
        else -> index + 1
    }
}
