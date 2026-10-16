package com.dewijones92.totum.data.queue

import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import java.time.Instant

/**
 * Where a queue entry came from — a local playlist, a podcast feed, a channel, or
 * an ad-hoc "Play all". Purely a **display** tag: playback never looks at it.
 */
public data class QueueGroup(public val id: String, public val title: String)

/**
 * One entry in the queue: what to play, plus the group it arrived with.
 *
 * The queue stays a **flat** list for playback — grouping is rendered by drawing a
 * header over each contiguous run of the same [group]. That means "what does next
 * mean at a group boundary?" never arises, dragging an entry out of a run simply
 * splits it, and there is no invariant to repair.
 */
public data class QueueEntry(
    public val item: PlayableItem,
    public val group: QueueGroup? = null,
    public val addedAt: Instant? = null,
)

public fun QueueSnapshot.stampedSince(before: QueueSnapshot, now: Instant): QueueSnapshot {
    if (entries.none { it.addedAt == null }) return this
    val known = before.entries.associate { it.item.item.id to it.addedAt }
    return copy(
        entries = entries.map { entry ->
            val id = entry.item.item.id
            when {
                entry.addedAt != null -> entry
                id in known -> entry.copy(addedAt = known[id])
                else -> entry.copy(addedAt = now)
            }
        },
    )
}

/**
 * The queue and where playback is within it.
 *
 * The playing item is a **member of the queue**, addressed by [currentIndex], rather
 * than living outside it. That is what makes jumping and advancing non-destructive:
 * moving the cursor leaves everything before it in place, so you can go back. It also
 * gives "peek" a real meaning — playing something *without* it joining the queue.
 *
 * [currentIndex] is [NOTHING_PLAYING] when playback isn't on a queue entry (nothing
 * has played yet, or the current item was peeked).
 */
public data class QueueSnapshot(
    public val entries: List<QueueEntry> = emptyList(),
    public val currentIndex: Int = NOTHING_PLAYING,
    public val anchor: MediaItemId? = null,
) {
    /** The entry playing now, or null when playback isn't on the queue. */
    public val current: QueueEntry? get() = entries.getOrNull(currentIndex)

    public val place: Int
        get() = currentIndex.takeIf { it >= 0 }
            ?: anchor?.let { id -> entries.indexOfFirst { it.item.item.id == id } }
            ?: NOTHING_PLAYING

    /** What follows the current entry — what "up next" means. */
    public val upNext: List<QueueEntry> get() = entries.drop((place + 1).coerceAtLeast(0))

    public companion object {
        public const val NOTHING_PLAYING: Int = -1
    }
}

/**
 * Persists the queue so it survives a restart — necessary once the queue is the
 * spine of playback with its own tab, rather than a transient side-car.
 *
 * Deliberately load/save rather than an observable: the in-memory queue is the
 * authority while the app runs, and an observable store would feed its own writes
 * back in.
 */
public interface QueueStore {

    /** The saved queue and cursor; empty when nothing was saved. */
    public suspend fun load(): QueueSnapshot

    /** Replaces the saved queue with [snapshot]. */
    public suspend fun save(snapshot: QueueSnapshot)
}
