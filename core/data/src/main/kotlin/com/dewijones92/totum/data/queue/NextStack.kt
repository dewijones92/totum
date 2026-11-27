package com.dewijones92.totum.data.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId

/**
 * The last item put "next", and what was playing when it was: the one place the rule for stacking
 * play-nexts is remembered (ADR 195). [tail] is where the next play-next goes after; [behind] is the
 * playing item the chain was built behind, so the chain ends the moment something else plays.
 */
public data class NextStack(public val tail: MediaItemId, public val behind: MediaItemId?)

public val QueueSnapshot.placeId: MediaItemId? get() = entries.getOrNull(place)?.item?.item?.id

private data class NextSpot(val after: MediaItemId?, val why: String)

private fun QueueSnapshot.nextSpot(moving: Set<MediaItemId>): NextSpot {
    val stack = nextStack ?: return NextSpot(null, "no earlier play-next to stack behind")
    val base = placeId
    if (stack.behind != base) {
        return NextSpot(
            null,
            "the playing item changed (the chain was behind ${stack.behind?.value}, now ${base?.value}), " +
                "so the chain starts again",
        )
    }
    var at = entries.indexOfFirst { it.item.item.id == stack.tail }
    if (at < 0) return NextSpot(null, "the last play-next ${stack.tail.value} is no longer queued")
    if (at <= place) {
        return NextSpot(
            null,
            "the last play-next ${stack.tail.value} is no longer ahead of the playing item (at #$at, place #$place)",
        )
    }
    while (at > place && entries[at].item.item.id in moving) at--
    if (at <= place) return NextSpot(null, "everything in the chain is being moved, so the chain starts again")
    return NextSpot(entries[at].item.item.id, "stacked after ${entries[at].item.item.id.value}")
}

/**
 * The one "Play next" rule. Removes [moving] from the queue, then puts [run] straight after the last
 * item that was put next, as long as that item is still queued, still ahead of the playing item and
 * the same item is still playing; otherwise straight after the playing item. [run] becomes the new
 * end of the chain. Every way of playing something next lands here: the phone's queue, a group header's
 * Play next, and a Pi target's queue.
 */
public fun QueueSnapshot.placingNext(moving: Set<MediaItemId>, run: List<QueueEntry>): QueueSnapshot {
    if (run.isEmpty()) return this
    val spot = nextSpot(moving)
    val without = removing { it.item.item.id in moving }
    val head = (without.place + 1).coerceIn(0, without.entries.size)
    val anchorAt = spot.after?.let { id -> without.entries.indexOfFirst { it.item.item.id == id } }?.takeIf { it >= 0 }
    val at = anchorAt?.plus(1) ?: head
    val placed = without.copy(
        entries = without.entries.take(at) + run + without.entries.drop(at),
        nextStack = NextStack(run.last().item.item.id, without.placeId),
    )
    Diag.log(
        "queue",
        "dewidebug play-next: ${run.size} item(s) [${run.take(LOGGED_IDS).joinToString { it.item.item.id.value }}] " +
            "at #$at of ${placed.entries.size} (${if (anchorAt != null) "anchor ${spot.after?.value}" else "head"}: " +
            "${spot.why}) [place=${without.place} playing=${without.placeId?.value} " +
            "tail=${placed.nextStack?.tail?.value}]",
    )
    return placed
}

private const val LOGGED_IDS = 5
