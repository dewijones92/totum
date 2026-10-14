package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag

internal const val FORWARD = 1
internal const val BACKWARD = -1

internal suspend fun PlaybackQueue.step(direction: Int): Boolean {
    val snapshot = state.value
    val playingId = nowPlaying.value?.item?.id
    val from = advanceFrom(snapshot, playingId)
    val way = if (direction == FORWARD) "after" else "before"
    var index = from + direction
    if (index !in snapshot.entries.indices) {
        Diag.log("queue", "nothing $way ${playingId?.value ?: "cursor $from"} of ${snapshot.entries.size}")
        return false
    }
    while (index in state.value.entries.indices) {
        val entry = state.value.entries[index]
        val title = entry.item.item.title.take(STEP_TITLE_CHARS)
        // Never advance onto the thing already playing. Belt and braces alongside the index
        // fix above: it also covers a duplicate that predates the de-duplication work.
        if (entry.item.item.id == playingId) {
            Diag.log("queue", "skipping index $index \"$title\" — it is what just played")
        } else if (playAt(index, rollBackOnRefusal = true)) {
            // The SUCCESS says what it advanced to, not just that it did. Only the refusals
            // said anything, so a report of a queue advancing wrongly showed "advance=true"
            // and nothing about which of sixty items it had landed on.
            val verb = if (direction == FORWARD) "advanced" else "went back"
            Diag.log("queue", "$verb from index $from to $index of ${state.value.entries.size} — \"$title\"")
            return true
        } else {
            // An item the player would not start. Silent before, so "nothing playable after
            // index 3" with a full queue gave no clue WHICH items were refused.
            Diag.warn("queue", "index $index \"$title\" would not play; trying the one $way it")
        }
        index += direction
    }
    Diag.log("queue", "nothing playable $way index $from")
    return false
}

private const val STEP_TITLE_CHARS = 60
