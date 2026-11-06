package com.dewijones92.totum.data.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueSnapshot.Companion.NOTHING_PLAYING
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.fillingSilenceFrom
import kotlin.random.Random

/**
 * Applies [place] to this snapshot with any existing copy of [item] removed, so re-adding
 * something MOVES it instead of duplicating it.
 *
 * Dewi hit this on "play next": pressing it on an item already in the queue left two copies.
 * playNow already de-duplicated and its documentation even claimed the behaviour was general,
 * so three of the four add-paths disagreed with the one that was right.
 *
 * The playing entry is deliberately exempt. Removing it would drop the cursor to -1 and the
 * queue would forget where it was, so "play next" on the thing already playing does nothing —
 * which is also the only sensible reading of the request.
 */
public fun QueueSnapshot.relocating(
    item: PlayableItem,
    place: (QueueSnapshot, PlayableItem) -> QueueSnapshot,
): QueueSnapshot {
    if (current?.item?.item?.id == item.item.id) return adoptingRoutesFrom(listOf(item))
    val earlier = entries.firstOrNull { it.item.item.id == item.item.id }?.item
    val kept = earlier?.let { old ->
        PlayableItem(item.item.fillingSilenceFrom(old.item), old.handle.mergedWith(item.handle))
    } ?: item
    if (earlier != null && kept != item) {
        Diag.log(
            "queue",
            "${item.item.id.value} queued again; kept what its row knew: ${kept.handle}"
        )
    }
    return place(removing { it.item.item.id == item.item.id }, kept)
}

public fun QueueSnapshot.appendingAll(items: List<PlayableItem>, group: QueueGroup?): QueueSnapshot {
    val playingId = current?.item?.item?.id
    val adopted = items.filter { it.item.id == playingId }.fold(
        this
    ) { acc, item -> acc.adoptingRoutesFrom(listOf(item)) }
    val lastAt = HashMap<MediaItemId, Int>()
    items.forEachIndexed { index, item -> lastAt[item.item.id] = index }
    val incoming = items.filterIndexed { index, item -> item.item.id != playingId && lastAt[item.item.id] == index }
    if (incoming.isEmpty()) return adopted
    val moving = incoming.mapTo(HashSet()) { it.item.id }
    val without = adopted.removing { it.item.item.id in moving }
    return without.copy(entries = without.entries + incoming.map { QueueEntry(it, group) })
}

public fun QueueSnapshot.insertingAllNext(items: List<PlayableItem>, group: QueueGroup? = null): QueueSnapshot {
    val playingId = current?.item?.item?.id
    val adopted = items.asReversed().filter { it.item.id == playingId }
        .fold(this) { acc, item -> acc.adoptingRoutesFrom(listOf(item)) }
    val seen = HashSet<MediaItemId>()
    val incoming = items.filter { it.item.id != playingId && seen.add(it.item.id) }
    if (incoming.isEmpty()) return adopted
    return adopted.removing { it.item.item.id in seen }.inserted(incoming.map { QueueEntry(it, group) })
}

/**
 * Gives the playing entry any route [fresh] knows about that it does not, leaving it in place.
 *
 * The playing entry is exempt from the remove-then-re-add that every other entry goes through, so
 * it alone never picks up a better handle — it keeps whatever it was created with until it stops
 * being current. A torrent queued before its audio-only URL existed therefore went on playing as
 * video for as long as it was the thing playing, while its neighbours in the same run got the URL.
 */
public fun QueueSnapshot.adoptingRoutesFrom(fresh: List<PlayableItem>): QueueSnapshot {
    val playing = current ?: return this
    val update = fresh.firstOrNull { it.item.id == playing.item.item.id } ?: return this
    val merged = playing.item.handle.mergedWith(update.handle)
    if (merged == playing.item.handle) return this
    Diag.log("queue", "playing entry adopted a fresher route: $merged")
    return copy(
        entries = entries.map {
            if (it.item.item.id == playing.item.item.id) {
                it.copy(item = it.item.copy(handle = merged))
            } else {
                it
            }
        },
    )
}

public fun QueueSnapshot.regrouping(id: MediaItemId?, group: QueueGroup): QueueSnapshot =
    copy(entries = entries.map { entry -> if (entry.item.item.id == id) entry.copy(group = group) else entry })

public fun QueueSnapshot.shuffledAfterCurrent(random: Random): QueueSnapshot {
    val after = (place + 1).coerceAtLeast(0)
    if (entries.size - after < 2) {
        Diag.log("queue", "shuffle up next: fewer than two items after the current one, nothing to shuffle")
        return this
    }
    return copy(entries = entries.take(after) + entries.drop(after).shuffled(random))
}

/** Inserts [run] immediately after the current entry, leaving the cursor put. */
public fun QueueSnapshot.inserted(run: List<QueueEntry>): QueueSnapshot {
    val at = (place + 1).coerceIn(0, entries.size)
    return copy(entries = entries.take(at) + run + entries.drop(at))
}

/** Drops matching entries, keeping the cursor on whatever it pointed at. */
public fun QueueSnapshot.removing(match: (QueueEntry) -> Boolean): QueueSnapshot {
    val kept = entries.filterNot(match)
    return copy(entries = kept, currentIndex = current?.let(kept::indexOf) ?: NOTHING_PLAYING)
}

public fun QueueSnapshot.removingAt(index: Int): QueueSnapshot =
    entries.getOrNull(index)?.let { removingAll(setOf(it.item.item.id)) } ?: this

public fun QueueSnapshot.removingAll(ids: Set<MediaItemId>): QueueSnapshot {
    val kept = entries.filterNot { it.item.item.id in ids }
    if (kept.size == entries.size) return this
    val at = place
    val keptBefore = entries.take(at.coerceAtLeast(0)).count { it.item.item.id !in ids }
    val placeRemoved = entries.getOrNull(at)?.item?.item?.id in ids
    val before = kept.getOrNull(keptBefore - 1)?.item?.item?.id
    return when {
        currentIndex in entries.indices && placeRemoved ->
            copy(entries = kept, currentIndex = NOTHING_PLAYING, anchor = before)
        currentIndex in entries.indices -> copy(entries = kept, currentIndex = keptBefore)
        placeRemoved -> copy(entries = kept, anchor = before)
        else -> copy(entries = kept)
    }
}
