package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/**
 * Gets playback going again after a stream stops for a reason the app can still act on, and
 * carries on from where it stopped.
 *
 * **Expired** — a streaming URL is a lease. YouTube signs one for a few hours, and after that every
 * request is a 403 — so pausing overnight and pressing play in the morning cannot work, no
 * matter how many times the player retries. It retried seventeen times in a real report
 * (0.1.170: paused 23:50 at 35 minutes in, resumed 06:07) and would have retried forever,
 * because nothing in the app knew the difference between "the network hiccuped" and "this
 * address is dead". The queue holds the stable watch URL, so a fresh one is always one
 * re-resolve away.
 *
 * **Unreachable** — the connection failed, and here the right move is the opposite: not a
 * fresh URL but *no request at all* until there is a network to make it on. The player lands
 * in IDLE and stays there forever on its own. Measured 2026-07-31 by black-holing HTTPS
 * mid-playback and then restoring it: the player sat at exactly 517805ms for over three
 * minutes with full connectivity, and would never have resumed. Stopping when the network
 * dies is right; staying stopped when it returns is not, and that is the tunnel case with the
 * screen off — the same "the queue just stopped" complaint that produced [StallWatchdog],
 * arriving by a different route.
 *
 * Both share the retry budget, because both need the same guard against an item that is
 * simply broken.
 *
 * Pillar-agnostic: it reacts to the failure signal and asks the queue to replay whatever is
 * current, which routes by pillar exactly as an ordinary play does.
 *
 * **The budget belongs to a stuck point, and a play nobody asked recovery for ends it.** Report
 * 0.1.383 is the whole argument: a video's signed URL 403'd from the first byte, the budget was
 * spent, and the item was skipped — correctly. Then Dewi tapped it again, twice, and each time it
 * jumped straight to the next item having made *no* attempt at all, because `attempts` was still
 * 3 and the position was still the one it died at. Two taps, two ERROR lines, zero
 * "re-resolving" lines between them. Choosing something by hand has to mean starting over.
 *
 * @param replay plays the current item from a position, returning whether it started.
 * @param moveOn starts the next queue entry, for when re-resolving has stopped helping.
 * @param freshStarts every play the queue began that recovery did not ask for — a tap, an
 *   auto-advance, a peek. Each one is a new stuck point: the budget resets and any retry still
 *   waiting out its backoff is abandoned.
 * @param isPlaying whether the player is *now* playing the given item. Checked after the backoff,
 *   because by then an earlier attempt may already have succeeded — in 0.1.383 attempt 3 armed a
 *   4s wait 370ms before attempt 2's replay started playing, then fired anyway, tore down a
 *   healthy stream and re-resolved into a 403. A retry has to look before it leaps.
 * @param forgetResolved drops the cached resolution of a failed item, so the NEXT play of it —
 *   including one the user asks for minutes later — cannot be handed the address that just died.
 *   Recovery's own replay already did this for itself; nothing did it for anyone else.
 * @param prefetchNext resolves whatever plays next, started on the FIRST failure so it is ready
 *   if the retries do not save this item. Measured on report 0.1.277: a video failed, three
 *   recoveries took 22 seconds, and only THEN did the next item begin a 25-second extraction —
 *   58 seconds of silence from first failure to sound, 28 of them after the app had already
 *   given up. Resolving in parallel costs nothing when recovery succeeds and removes almost all
 *   of that when it does not.
 * @param awaitNetwork suspends until there is a usable connection. Only consulted for an
 *   [StreamFailure.Reason.Unreachable], so an expiry is never delayed by it.
 */
internal class StreamRecovery(
    private val failures: Flow<StreamFailure>,
    private val replay: suspend (Long) -> Boolean,
    private val moveOn: suspend () -> Boolean,
    private val freshStarts: Flow<MediaItemId> = emptyFlow(),
    private val isPlaying: (MediaItemId) -> Boolean = { false },
    private val forgetResolved: (MediaItemId) -> Unit = {},
    private val prefetchNext: suspend () -> Unit = {},
    private val awaitNetwork: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val backoffMs: Long = BACKOFF_MS,
) {
    private var lastItem: MediaItemId? = null
    private var lastPositionMs = 0L
    private var attempts = 0

    /**
     * Bumped by every fresh start. A retry captures it before its backoff and abandons if it has
     * moved on waking, which is how a tap during the wait cancels the retry it would otherwise
     * race — the failure collector is asleep in [delay] and cannot notice on its own.
     */
    @Volatile
    private var generation = 0

    fun start() {
        scope.launch {
            failures.collect(::recover)
        }
        scope.launch {
            freshStarts.collect { itemId ->
                generation++
                attempts = 0
                lastItem = itemId
                lastPositionMs = 0
                Diag.log("playback", "fresh start of ${itemId.value} — recovery starts over")
            }
        }
    }

    private suspend fun recover(failure: StreamFailure) {
        // Before anything else, and for every failure: whatever address just died must not be
        // handed out again. Recovery's own replay forgets it, but only for itself — so a tap on
        // the same item afterwards got a cache hit on the dead URL and failed before it began.
        runCatching { forgetResolved(failure.itemId) }
            .onFailure { Diag.warn("playback", "could not forget the failed stream for ${failure.itemId.value}", it) }
        val generationAtFailure = generation
        if (failure.shouldResetBudget()) {
            attempts = 0
        }
        val firstFailureForThisItem = attempts == 0
        lastItem = failure.itemId
        lastPositionMs = failure.positionMs

        if (firstFailureForThisItem) {
            // In parallel with the retries, not after them. An extraction costs 20-25s on a real
            // phone, so starting it only once recovery has given up puts that whole cost into
            // silence the user is already sitting through. Fire-and-forget: if recovery works the
            // resolved result simply waits in the cache, and if it does not, the next item is
            // ready the moment we move on.
            Diag.log("playback", "resolving the next item too, in case this one cannot be saved")
            scope.launch { runCatching { prefetchNext() } }
        }

        if (attempts >= maxAttempts) {
            // Giving up on THIS item is the point — re-resolving forever against something
            // genuinely gone would be the same infinite loop wearing a different hat. But
            // giving up on the whole queue is not: a real report had the player dead on one
            // item with 58 more behind it, going nowhere. So move on, and say so.
            Diag.warn("playback", "stream still failing after $attempts recoveries; skipping it")
            if (!moveOn()) {
                Diag.warn("playback", "nothing left in the queue to move on to")
            }
            return
        }
        attempts++
        if (failure.reason == StreamFailure.Reason.Unreachable) {
            // No request until there is something to make it on. Retrying into a dead
            // network would burn the budget without ever having had a chance.
            Diag.log("playback", "stream unreachable at ${failure.positionMs}ms — waiting for a network")
            awaitNetwork()
            Diag.log("playback", "network is back — resuming from ${failure.positionMs}ms")
            // This is the longest wait there is — a tunnel can be minutes — so it is the one most
            // likely to be overtaken by the user picking something else.
            if (overtaken(failure, generationAtFailure)) return
        } else {
            Diag.log("playback", "re-resolving expired stream (attempt $attempts) from ${failure.positionMs}ms")
        }
        // A retry with no gap is not a retry. Measured on the emulator 2026-07-31 with
        // packets dropped while Android still reported a validated network: the whole
        // three-attempt budget was spent in 56 MILLISECONDS and the item skipped, because
        // each replay failed the instant it was tried. Weak signal and captive portals look
        // exactly like that, so the guard against a dead item was skipping live ones.
        if (attempts > 1) {
            val backoff = backoffMs * (attempts - 1)
            Diag.log("playback", "waiting ${backoff}ms before attempt $attempts, so the retry is a real one")
            delay(backoff)
            if (overtaken(failure, generationAtFailure)) return
        }
        if (!replay(failure.positionMs)) {
            Diag.warn("playback", "could not replay after ${failure.reason} — nothing current, or it would not resolve")
        }
    }

    /**
     * Whether the wait we just came out of has been overtaken, in which case this attempt is
     * pointless and possibly harmful. Both waits — the backoff and the one for a network — end
     * here, because time passing is the only thing that makes either case possible.
     *
     * The second case hands the budget back: if the item is playing again, an earlier attempt
     * worked and the stuck point is genuinely over.
     */
    private fun overtaken(failure: StreamFailure, generationAtFailure: Int): Boolean {
        if (generation != generationAtFailure) {
            Diag.log(
                "playback",
                "dropping attempt $attempts for ${failure.itemId.value} — something else started while it waited",
            )
            return true
        }
        if (isPlaying(failure.itemId)) {
            Diag.log(
                "playback",
                "dropping attempt $attempts — ${failure.itemId.value} is playing again, the stuck point is over",
            )
            attempts = 0
            return true
        }
        return false
    }

    /**
     * A retry budget is per stuck point, not per item. Playing on and expiring again later
     * is a different, legitimate failure — a long listen crosses more than one lease — so
     * real progress since the last one earns a fresh budget. Without this, three expiries in
     * one sitting would permanently disable recovery for that item.
     */
    private fun StreamFailure.shouldResetBudget(): Boolean =
        itemId != lastItem || positionMs > lastPositionMs + PROGRESS_MS

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** Multiplied by the attempt number, so the second waits 2s and the third 4s. */
        const val BACKOFF_MS = 2_000L

        /** Playback this much further on means the previous re-resolve worked. */
        const val PROGRESS_MS = 30_000L
    }
}
