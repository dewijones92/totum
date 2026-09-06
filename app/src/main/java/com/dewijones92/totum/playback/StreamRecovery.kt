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
 * @param playWithoutTheStream plays the current item from a copy on the disk, tried BEFORE moving
 *   on. An audio-only download does not stand in while you are watching a working stream — Dewi's
 *   call — but once the stream has failed every retry there is no working stream to prefer, and
 *   report 0.1.383 skipped past a video whose audio was already downloaded. Returns false when
 *   there is nothing on the disk, which is when moving on is the right answer.
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
    private val playWithoutTheStream: suspend (Long) -> Boolean = { false },
    /**
     * Plays the current item's SOUND without its picture, from a position — the last rung before the
     * item is abandoned.
     *
     * Measured 2026-08-18 on a 97-minute video, across every client yt-dlp can reach: 19 video formats
     * with a URL, **none** carrying a solved `n`, against 77 audio formats of which 73 do. YouTube
     * serves video only over SABR to the clients that would attest, so watching a long video past its
     * first megabyte cannot be fixed by choosing better — there is nothing better to choose. Seeking an
     * hour in failed about seven times in ten while the audio-only URL served the same offset 5 of 5.
     *
     * Losing the picture is a poor outcome; silence is a broken app, and this is a listening app. The
     * streaming twin of the download rule Dewi settled on 2026-08-14: once the stream has failed every
     * retry the choice is "audio or nothing", and skipping is the worse answer.
     */
    private val playWithoutThePicture: suspend (Long) -> Boolean = { false },
    /**
     * Plays the current item over SABR — YouTube's own streaming protocol — keeping the picture when
     * the ordinary stream URLs have been refused.
     *
     * The app has carried a complete SABR client since July and it was switched OFF, behind an
     * experimental setting defaulting to false, while the failure it solves happened for real: a 403
     * from `ANDROID_VR` on a URL with 21577s of lease left, one retry, then the picture given up. A
     * working route behind a gate the real failure never opens, for the fourth time in one day.
     *
     * It stays off as the PRIMARY route, deliberately: SABR caps at 1080p30 and cannot seek, which is
     * a poor default for every video. As a rescue it costs nothing when the ordinary route works, and
     * a capped picture beats no picture — the only comparison that matters this far down the ladder.
     *
     * Returns false when SABR cannot serve this item — a format it refuses, no endpoint, no config.
     * Whether it is even OFFERED is decided here, by position: see [SABR_START_WINDOW_MS].
     */
    private val playOverSabr: suspend (Long) -> Boolean = { false },
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
     * How many times the rungs BELOW a working stream have rescued this stuck point.
     *
     * Its own counter, because `attempts` is deliberately reset by a successful rescue and therefore
     * cannot bound one. Both network rungs zeroed the budget -- copying the disk rung, where it is safe
     * because a local file cannot 403 -- and neither emits `freshStarts` nor bumps `generation`, so every
     * failure of a rescue walked the ladder again from the top and [moveOn] was unreachable. In AUDIO or
     * metered AUTO mode the failing stream IS the audio-only one, so the rung replayed the identical dead
     * URL at a 10-25s re-extraction per cycle: the queue parked on one item, re-fetching in the person's
     * pocket, never advancing.
     *
     * Reset only where the budget legitimately starts over -- a fresh start, or real progress -- so a
     * long listen that crosses several leases still gets rescued each time.
     */
    private var rescues = 0

    /**
     * Bumped by every fresh start. A retry captures it before its backoff and abandons if it has
     * moved on waking, which is how a tap during the wait cancels the retry it would otherwise
     * race — the failure collector is asleep in [delay] and cannot notice on its own.
     */
    @Volatile
    private var generation = 0

    /**
     * The item the QUEUE last started on its own — a tap, an auto-advance, a peek — never one of
     * recovery's own replays (those do not emit [freshStarts]). It is the truth about what is
     * current, and it is separate from [lastItem], which [recover] overwrites with whatever failure
     * it is handling. A failure for anything else is stale and must be ignored: without this, the
     * previous item's recovery, arriving after the next item has started, called [replay] — which
     * acts on the current item — and so replayed the NEW item at the OLD item's dead position (the
     * 2026-08-31 CI cross-item leak).
     */
    @Volatile
    private var currentStartedItem: MediaItemId? = null

    fun start() {
        scope.launch {
            failures.collect(::recover)
        }
        scope.launch {
            freshStarts.collect { itemId ->
                generation++
                attempts = 0
                lastItem = itemId
                currentStartedItem = itemId
                lastPositionMs = 0
                rescues = 0
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
        // Stale failures do nothing beyond forgetting their dead URL above. `replay` acts on the
        // queue's CURRENT item, so acting on a failure for anything else replays the wrong item at
        // the wrong position — the cross-item leak. Only drop when we positively know it is stale;
        // a null means no fresh start has been seen yet, and blocking then would break app-start.
        val current = currentStartedItem
        if (current != null && failure.itemId != current) {
            Diag.log(
                "playback",
                "ignoring a stale failure for ${failure.itemId.value} at ${failure.positionMs}ms — " +
                    "the queue has moved on to ${current.value}",
            )
            return
        }
        val generationAtFailure = generation
        if (failure.shouldResetBudget()) {
            attempts = 0
            rescues = 0
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

        val budget = failure.budget()
        if (attempts >= budget) {
            giveUpOnTheStream(failure, attempts)
            return
        }
        attempts++
        if (failure.reason == StreamFailure.Reason.Unreachable) {
            // No request until there is something to make it on. Retrying into a dead
            // network would burn the budget without ever having had a chance.
            Diag.log("playback", "stream unreachable at ${failure.positionMs}ms — waiting for a network")
            awaitNetwork()
            Diag.log("playback", "network is back — resuming ${failure.itemId.value} from ${failure.positionMs}ms")
            // This is the longest wait there is — a tunnel can be minutes — so it is the one most
            // likely to be overtaken by the user picking something else.
            if (overtaken(failure, generationAtFailure)) return
        } else {
            // The reason and the budget, not the word "expired". Every one of these lines used to
            // say "expired" whatever had happened, including fourteen times in 0.1.390 for URLs
            // with six hours of lease left — so the trail asserted the diagnosis that was wrong.
            Diag.log(
                "playback",
                "re-resolving after ${failure.reason} (attempt $attempts of $budget) " +
                    "from ${failure.positionMs}ms",
            )
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
     * What to do once re-resolving has stopped helping: the rungs below a working stream, best first.
     *
     * Its own function because it is a distinct decision from "should I retry", and because [recover]
     * had reached the length where the two read as one thing. The ORDER is the substance: a copy on the
     * disk costs no data and cannot stall, the sound without the picture keeps the item playing, and
     * moving on is what is left.
     */
    private suspend fun giveUpOnTheStream(failure: StreamFailure, spent: Int) {
        // Giving up on THIS item is the point — re-resolving forever against something
        // genuinely gone would be the same infinite loop wearing a different hat. But
        // giving up on the whole queue is not: a real report had the player dead on one
        // item with 58 more behind it, going nowhere. So move on, and say so.
        Diag.warn("playback", "stream still failing after $spent recoveries; giving up on the stream")
        // Before abandoning the item, ask whether it is already on the disk. The rule that an
        // audio-only copy must not silently replace a video you are watching assumes a working
        // stream to prefer; once there is not one, the choice is audio or nothing, and report
        // 0.1.383 had the audio sitting downloaded through three failed attempts and a skip.
        if (playWithoutTheStream(failure.positionMs)) {
            Diag.log("playback", "playing ${failure.itemId.value} from the copy on disk instead")
            attempts = 0
            // NOT counted as a rescue: a local file cannot 403, so it cannot loop.
            return
        }
        // The ladder's own budget, checked AFTER the disk rung. Above it, a copy that finished
        // downloading DURING the recovery was never consulted -- which is the exact report-0.1.383 harm
        // the disk rung was added for. The disk rung stays exempt from counting because it is the one
        // rung that cannot come back round: a local file is not re-fetched.
        if (rescues >= MAX_RESCUES) {
            Diag.warn(
                "playback",
                "already rescued ${failure.itemId.value} $rescues time(s) at this stuck point and it " +
                    "keeps failing; moving on rather than rescuing it again",
            )
            abandon()
            return
        }
        // The protocol YouTube is actually serving, before the picture is given up for good. Below
        // the disk (which is full quality and free) and above the sound (which loses the picture).
        //
        // Only near the start. SABR asks for a media TIME and cannot yet begin at an arbitrary
        // offset, so hour-deep it would either fail slowly or — far worse — "succeed" from the top
        // and throw away someone's place, which is the exact harm `listen()` did before it was
        // given the position. The guard lives HERE rather than in the wiring so the rule is visible
        // at the decision and provable by a test, instead of resting on a lambda's good manners.
        if (failure.positionMs > SABR_START_WINDOW_MS) {
            Diag.log(
                "playback",
                "not trying SABR for ${failure.itemId.value} at ${failure.positionMs}ms — " +
                    "it cannot start mid-item, and restarting would lose your place",
            )
        } else if (playOverSabr(failure.positionMs)) {
            Diag.warn(
                "playback",
                "playing ${failure.itemId.value} over SABR instead — the ordinary stream was refused, " +
                    "so the picture is capped at 1080p30 rather than lost",
            )
            attempts = 0
            rescues++
            return
        }
        // The sound, if the picture is all that was refused. Tried AFTER the disk (a copy costs no
        // data and cannot stall) and BEFORE moving on, because a video playing as audio is still
        // the thing the person asked for.
        if (playWithoutThePicture(failure.positionMs)) {
            Diag.warn(
                "playback",
                "keeping the sound for ${failure.itemId.value} without its picture — the video " +
                    "stream was refused and no copy is on disk",
            )
            attempts = 0
            rescues++
            return
        }
        Diag.warn("playback", "nothing on disk for ${failure.itemId.value} either; skipping it")
        abandon()
    }

    /** Steps to the next item, saying so when there is nothing to step to. */
    private suspend fun abandon() {
        if (!moveOn()) {
            Diag.warn("playback", "nothing left in the queue to move on to")
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

    /**
     * How many re-resolves this kind of failure is worth.
     *
     * A [StreamFailure.Reason.Rejected] gets ONE, because the URL's own lease says the address is
     * alive and being turned away — and a newly-signed one is turned away identically. Report
     * 0.1.390 proved it three times over: `expire` 1787013066, then …073, then …081, each freshly
     * signed and each 403 within 150ms, at 12–18 seconds of extraction apiece. The one attempt is
     * kept because a single bad CDN node is real and a fresh URL can land elsewhere; the other two
     * only delayed reaching the audio that was already on the disk.
     *
     * An [StreamFailure.Reason.Expired] keeps the full budget — 0.1.170, paused overnight and
     * resumed in the morning, is genuinely fixed by a fresh URL.
     */
    private fun StreamFailure.budget(): Int =
        if (reason == StreamFailure.Reason.Rejected) REFUSED_MAX_ATTEMPTS else maxAttempts

    private companion object {
        const val MAX_ATTEMPTS = 3

        /** One, so a bad CDN node is covered and a refusing client is not argued with. */
        const val REFUSED_MAX_ATTEMPTS = 1

        /**
         * How many times the rungs below a working stream may rescue ONE stuck point.
         *
         * Two, so a genuinely re-resolved audio URL gets a second chance while a dead one cannot loop.
         * The ladder must always be able to reach [moveOn]; this is what guarantees it.
         */
        const val MAX_RESCUES = 2

        /** Multiplied by the attempt number, so the second waits 2s and the third 4s. */
        const val BACKOFF_MS = 2_000L

        /** Playback this much further on means the previous re-resolve worked. */
        const val PROGRESS_MS = 30_000L

        /**
         * How far in SABR is still worth trying. Generous enough to cover a failure during the
         * opening seconds — which is when a refusal almost always lands, the first megabyte being
         * exactly what an unattested client is given — and short enough that no real listening
         * position is ever restarted from zero.
         */
        const val SABR_START_WINDOW_MS = 10_000L
    }
}
