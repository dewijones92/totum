package com.dewijones92.totum.domain

import kotlinx.coroutines.flow.Flow

/**
 * The account position this device has already taken into account, per item.
 *
 * Without it a stale remote position is indistinguishable from a fresh one, and that made rewinding
 * impossible (Dewi, report 0.1.496, 2026-09-20: *"I have tried to rewind the video back to the start
 * but it is not working"*). YouTube held `vceHVwxOnhA` at 77700ms, he rewound to 0 six times, and
 * every re-entry came back `REMOTE_IS_AHEAD [local=11273 youtube=77700]` — the same number each
 * time, because the outbound half was refused (`held=123`) and so the account's opinion could never
 * move. [resumeFrom] was comparing values when the question is whether the remote has anything NEW
 * to say.
 *
 * So the remote number is recorded the moment it is used, and a number this device has already
 * acted on never outranks what has happened here since. Watching forty minutes on the TV still
 * wins, because that moves the number.
 *
 * **The invariant this creates:** anything that DELETES an item's local progress must clear its
 * entry here too, or the recorded figure becomes a permanent veto and the item can never again
 * take the account's position. Today the only deleter is "mark unplayed", where the veto is what
 * the person asked for; a future history purge or partial restore would not be, and no test
 * would catch it.
 */
public interface ReconciledAccountProgress {

    /** The account position already acted on for [itemId], or null if none ever was. */
    public suspend fun reconciledMs(itemId: MediaItemId): Long?

    /** Records that [remoteMs] has now been acted on for [itemId]. */
    public suspend fun reconcile(itemId: MediaItemId, remoteMs: Long)

    /** Everything acted on so far, live — rows need the same inputs as a tap or the two disagree. */
    public fun observeReconciled(): Flow<Map<MediaItemId, Long>>

    /**
     * Forgets everything, because these figures belong to ONE account.
     *
     * Called on sign-out. Without it a figure recorded against the previous account is compared
     * against the new one's, and a numeric coincidence silently vetoes a perfectly good position.
     */
    public suspend fun forgetAll()
}

/** Remembers nothing, so [resumeFrom] behaves exactly as it did before this existed. */
public object NoReconciledAccountProgress : ReconciledAccountProgress {
    override suspend fun reconciledMs(itemId: MediaItemId): Long? = null
    override suspend fun reconcile(itemId: MediaItemId, remoteMs: Long): Unit = Unit
    override fun observeReconciled(): Flow<Map<MediaItemId, Long>> = kotlinx.coroutines.flow.flowOf(emptyMap())
    override suspend fun forgetAll(): Unit = Unit
}
