package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag

suspend fun PlaybackQueue.playCurrentOrNext(why: String): Boolean {
    val snapshot = state.value
    val played = if (snapshot.currentIndex >= 0) playAt(snapshot.currentIndex) else playNextInQueue()
    Diag.log(
        "queue",
        "$why: played=$played from " + if (snapshot.currentIndex >= 0) {
            "the current entry ${snapshot.currentIndex} of ${snapshot.entries.size}"
        } else {
            "after ${snapshot.anchor?.value ?: "the start"} (nothing current)"
        },
    )
    return played
}
