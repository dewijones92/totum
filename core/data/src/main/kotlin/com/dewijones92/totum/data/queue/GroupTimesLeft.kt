package com.dewijones92.totum.data.queue

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.TimeLeft
import com.dewijones92.totum.domain.timeLeft

public fun groupTimesLeft(
    entries: List<QueueEntry>,
    from: Int,
    chosenSpeed: Float,
    countsAsLeft: (PlayableItem) -> Boolean,
    stateOf: (MediaItemId) -> PlayState,
): Map<String, TimeLeft> = entries.withIndex()
    .mapNotNull { (index, entry) ->
        entry.group?.takeIf { index >= from && countsAsLeft(entry.item) }?.let { it.id to entry.item.item }
    }
    .groupBy({ it.first }, { it.second })
    .mapNotNull { (id, items) -> timeLeft(items, chosenSpeed, stateOf)?.let { id to it } }
    .toMap()
