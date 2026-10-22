package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItemId
import kotlin.random.Random

private const val TOGETHER_PREFIX = "together:"

fun PlaybackQueue.shuffleTogether(ids: Set<MediaItemId>, fallbackTitle: String, random: Random = Random.Default): Int {
    val chosen = state.value.toMixIn(ids)
    if (chosen.size < 2) {
        Diag.log(
            "queue",
            "shuffle together: ${chosen.size} item(s) still to play of ${ids.size} ticked; nothing to mix",
        )
        return 0
    }
    val group = QueueGroup("$TOGETHER_PREFIX${System.currentTimeMillis()}", chosen.togetherTitle(fallbackTitle))
    val from = chosen.groupBy { it.group?.title ?: "ungrouped" }.mapValues { it.value.size }
    reorder("shuffle together x${chosen.size} as \"${group.title}\" from $from") {
        it.shufflingTogether(ids, group, random)
    }
    return chosen.size
}

internal fun QueueSnapshot.shufflingTogether(ids: Set<MediaItemId>, group: QueueGroup, random: Random): QueueSnapshot {
    val members = toMixIn(ids)
    if (members.size < 2) return this
    val at = entries.indexOf(members.first())
    val others = entries.filterNot { it in members }
    val mixed = members.shuffled(random).map { it.copy(group = group) }
    return rebuiltFrom(others.take(at) + mixed + others.drop(at))
}

private fun QueueSnapshot.toMixIn(ids: Set<MediaItemId>): List<QueueEntry> {
    val after = (place + 1).coerceAtLeast(0)
    return entries.drop(after).filter { it.item.item.id in ids }
}

internal fun List<QueueEntry>.togetherTitle(fallback: String): String {
    val titles = mapNotNull { it.group?.title?.removePrefix("🔀 ") }.distinct()
    return if (titles.isEmpty()) fallback else "🔀 " + titles.joinToString(" + ")
}
