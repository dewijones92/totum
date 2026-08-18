package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Moves on when an item stops at its own end and never reports finishing.
 *
 * [AutoAdvancer] waits for the player to say `hasEnded`, and [ExpiredStreamRecovery] waits
 * for it to raise an error. A stall is neither: the player sits in BUFFERING with the
 * position frozen, forever, and both watchers are blind to it. With the screen off that is
 * indistinguishable from the queue simply stopping.
 *
 * A real report (0.1.230, 2026-07-31): a 41-minute video reached 2506062ms — inside the last
 * ten seconds, with watch-history already reporting it finished — went to BUFFERING at
 * 07:55:48 and was still at exactly that position 46 seconds later, across two 30-second
 * snapshots, until Dewi gave up and picked the next item by hand. Sixty-five items were
 * queued behind it.
 *
 * So: a position that has not moved for [STALL_MS] while buffering, within [END_MS] of the
 * duration, means this item is over whatever the player believes. Advance.
 *
 * **It SAMPLES the state on a clock rather than collecting it, and that is the whole trick.**
 * `PlaybackController.state` is a `StateFlow`, which drops a value equal to the one before
 * it — and a stall is by definition a run of identical states: same item, same position, same
 * buffering flag. A collector therefore gets exactly one emission when the stall begins and
 * then silence, so a timer driven by emissions would only ever be read at zero elapsed and
 * would never fire. Nothing about that failure is visible in a log; it just quietly does
 * nothing. The unit tests caught it before it shipped, which is why they tick a frozen state.
 *
 * A stall EARLIER in an item is rescued differently: by replaying it from where it stopped with
 * a freshly resolved URL, never by advancing. Skipping a video because it hiccuped in the middle
 * would be a worse bug than the hiccup.
 *
 * That case used to be logged and left alone, on the grounds that re-resolving mid-item was a
 * behaviour change with *"not one observation of it yet to design against"*. Report 0.1.332 is
 * that observation. A Dwarkesh video froze at 652353ms with 48ms buffered on a connection
 * measuring 125Mbps; this watchdog saw it at 20 seconds, said *"not at the end, so leaving it to
 * the player"*, and the player never recovered. Four consecutive 30-second snapshots show the
 * position unchanged. It ended after **2 minutes 16 seconds** because Dewi dismissed the player
 * and pressed play again — and the log shows what that did: a fresh `extract` and a new
 * googlevideo URL, after which it played on normally.
 *
 * So the recovery that works was already in the codebase ([PlaybackQueue.replayCurrent], which
 * [ExpiredStreamRecovery] uses for expired streams). Nothing triggered it, because a request that
 * hangs raises no error — and an error is the only thing that recovery listens for. A hang is
 * silent, so the only watcher that can see it is this one, sampling the clock.
 */
internal class StallWatchdog(
    private val states: StateFlow<PlaybackState?>,
    private val advance: suspend () -> Boolean,
    /**
     * Re-resolves the current item and plays it again from the given position — the rescue for a
     * stall that is NOT at the end, and the thing Dewi had to do by hand for 2m16s.
     */
    private val replay: suspend (Long) -> Boolean,
    private val isEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val checkEveryMs: Long = CHECK_MS,
) {
    private var stuckItem: MediaItemId? = null
    private var stuckPositionMs = -1L
    private var stalledForMs = 0L

    /**
     * How far into THIS stall the last decision was taken, so a stall that goes on gets escalated.
     *
     * The guard used to be "once per item", re-armed only when the position changed — and a replay
     * seeks back to where it stalled, so on a genuinely dead stream the position never changes and
     * nothing further ever happened. Proven on the emulator: two rescues fired at 4812ms and the
     * give-up was unreachable. Escalating on elapsed time needs no re-arming, which a frozen player
     * cannot provide by definition.
     */
    private var actedAtStalledMs = 0L

    /**
     * The stall already rescued, so a frozen player cannot advance the queue over and over.
     *
     * Cleared the moment that item makes progress again. It used to be set once per item and
     * kept forever, which is the same defect that broke [AutoAdvancer]: an item rescued once
     * could never be rescued again, so replaying it and stalling again left the queue stopped
     * with nothing in the log to say why. Fixed here before it was ever reported, because the
     * two were the same three lines written twice.
     */
    private var handled: MediaItemId? = null

    /**
     * How many times this item has been rescued without ever getting anywhere.
     *
     * A rescue moves the position (it re-prepares and seeks), which clears [handled] and makes the
     * next stall eligible — so a stream that is genuinely dead was replayed every ~25 seconds
     * forever, restarting the spinner each time and saying nothing to the person watching. Bounded
     * because "keep trying the same dead address" stops being a rescue after the second go.
     *
     * Reset only by GENUINE forward progress, not by the position change the replay itself causes.
     */
    private var rescues = 0
    private var rescuedItem: MediaItemId? = null
    private var rescuedAtMs = -1L

    /**
     * The item already given up on, so giving up happens ONCE.
     *
     * Needed because giving up moves the position too, which clears the once-per-item guard — so
     * without this the queue was advanced again on every subsequent stall of the same item. That is
     * not hypothetical: if the advance fails (nothing playable after it), the dead item stays
     * current and would be skipped over and over.
     */
    private var abandoned: MediaItemId? = null

    fun start() {
        Diag.log("advance", "watching for stalls (a frozen buffer at the end of an item)")
        scope.launch {
            while (true) {
                delay(checkEveryMs)
                check(states.value)
            }
        }
    }

    private suspend fun check(state: PlaybackState?) {
        // A paused player has a frozen position too, and is not stuck — only a player that
        // is trying to load something can be.
        // INTENT, not motion. `isPlaying` is false for a genuine stall too, so gating on it would
        // disable the watchdog; `wantsToPlay` is the player's own playWhenReady and is the only thing
        // that separates "trying and failing" from "paused". Pausing does NOT leave the buffering state
        // (Media3 1.10.1: pausing calls no setState, and the only exit from BUFFERING never consults
        // playWhenReady -- ExoPlayer's own stuck detector gates itself on shouldPlayWhenReady for the
        // same reason), so without this a pause during a starved buffer -- headphones out, audio focus
        // lost, a lock-screen tap -- was byte-identical to a stall, and twenty seconds later the app
        // re-prepared and PLAYED itself out of the phone's speaker.
        if (state == null || !state.isStalling()) {
            stuckItem = null
            return
        }
        if (state.itemId != stuckItem || state.positionMs != stuckPositionMs) {
            noteProgress(state)
            return
        }
        stalledForMs += checkEveryMs
        if (stalledForMs < STALL_MS || abandoned == state.itemId) return
        // Another full window since the last decision, so one continuous stall escalates:
        // rescue, rescue, give up — rather than acting once and waiting for a movement that a
        // frozen player will never make.
        if (stalledForMs - actedAtStalledMs < STALL_MS) return
        actedAtStalledMs = stalledForMs

        // "starved" vs "stuck" is the question a stall report has never been able to answer,
        // and it decides whether the fix is a fresh URL or a nudge to the player.
        val bufferedAheadMs = state.bufferedPositionMs - state.positionMs
        // Against a floor rather than zero. The threshold used to be `> 0`, which called 48ms
        // "STUCK" — implying the player holds data and needs a nudge — when 48ms of a 1080p
        // stream is starvation in every practical sense, and the fix for it is a fresh URL.
        val diagnosis = if (bufferedAheadMs > STARVED_UNDER_MS) {
            "STUCK (${bufferedAheadMs}ms buffered)"
        } else {
            "STARVED (only ${bufferedAheadMs}ms buffered)"
        }

        val remainingMs = state.durationMs?.minus(state.positionMs)
        if (remainingMs == null || remainingMs > END_MS) {
            rescueOrGiveUp(state, diagnosis, remainingMs)
            return
        }
        advanceAtEnd(state, diagnosis, remainingMs)
    }

    /** Bookkeeping for a player that has moved — which is also how a rescue is judged to have worked. */
    /**
     * Whether this state is a STALL rather than a pause.
     *
     * `isPlaying` is false for both, so it cannot separate them; intent can. Pausing does not leave the
     * buffering state (Media3 1.10.1: pausing calls no `setState`, and the only exit from BUFFERING never
     * consults `playWhenReady` — ExoPlayer's own stuck detector gates itself on `shouldPlayWhenReady` for
     * exactly this reason). Without the intent check, a pause during a starved buffer — headphones out,
     * audio focus lost, a lock-screen tap — was byte-identical to a stall, and twenty seconds later this
     * watchdog re-prepared and played the item out of the phone's speaker.
     */
    private fun PlaybackState.isStalling(): Boolean {
        if (!isBuffering) return false
        if (wantsToPlay) return true
        if (stuckItem != null) {
            Diag.log("playback", "buffering but PAUSED — not a stall, so there is nothing to rescue")
        }
        return false
    }

    private fun noteProgress(state: PlaybackState) {
        // A stall that recovers is the only evidence there will ever be for how long a
        // NORMAL re-buffer lasts, which is what the STALL_MS threshold is guessing at.
        if (stalledForMs >= NOTEWORTHY_MS && stuckItem != null) {
            Diag.log(
                "advance",
                "${stuckItem?.value} recovered after ${stalledForMs}ms stuck at ${stuckPositionMs}ms",
            )
        }
        stuckItem = state.itemId
        stuckPositionMs = state.positionMs
        stalledForMs = 0
        actedAtStalledMs = 0
        // A different item starts with a clean slate; nothing about the last one's failures
        // says anything about this one.
        if (rescuedItem != state.itemId) resetRescues(state.itemId)
        // Genuine forward progress — PAST where the rescue put us, not merely different from
        // it. Without the margin the replay's own seek reads as success and the budget never
        // depletes.
        if (rescues > 0 && state.positionMs > rescuedAtMs + PROGRESS_MS) {
            Diag.log("advance", "${state.itemId.value} is genuinely playing again after $rescues rescue(s)")
            resetRescues(state.itemId)
        }
        // Progress means any earlier rescue of this item is spent, not a reason to refuse
        // the next one.
        if (handled == state.itemId) {
            Diag.log("advance", "${state.itemId.value} is moving again; its earlier stall no longer counts")
            handled = null
        }
    }

    /**
     * Mid-item: replay from where it stopped, or stop trying once the budget is spent.
     *
     * Never advances while a rescue remains — skipping a video someone is watching is a worse
     * outcome than the stall.
     */
    private suspend fun rescueOrGiveUp(state: PlaybackState, diagnosis: String, remainingMs: Long?) {
        if (rescues >= MAX_RESCUES) {
            // Out of rescues. Moving on is what a person does after the third restart, and it at
            // least keeps the queue alive; replaying a dead stream forever has nothing to
            // recommend it.
            Diag.warn(
                "advance",
                "${state.itemId.value} stalled again at ${state.positionMs}ms after $rescues " +
                    "rescue(s) — $diagnosis. Giving up on this stream and moving on",
            )
            abandoned = state.itemId
            if (isEnabled()) {
                Diag.log("advance", "${state.itemId.value} exhausted rescues advance=${advance()}")
            } else {
                Diag.log("advance", "${state.itemId.value} is unplayable but auto-play next is off")
            }
            return
        }
        rescues++
        rescuedItem = state.itemId
        rescuedAtMs = state.positionMs
        Diag.warn(
            "advance",
            "${state.itemId.value} stalled ${stalledForMs}ms at ${state.positionMs}ms — $diagnosis, " +
                "${remainingMs}ms left — replaying it from there with a fresh stream " +
                "(rescue $rescues of $MAX_RESCUES)",
        )
        Diag.log("advance", "${state.itemId.value} stall replay=${replay(state.positionMs)}")
    }

    /** At its end and frozen: whatever the player believes, this item is over. */
    private suspend fun advanceAtEnd(state: PlaybackState, diagnosis: String, remainingMs: Long?) {
        if (handled == state.itemId) return
        handled = state.itemId
        if (!isEnabled()) {
            Diag.log(
                "advance",
                "${state.itemId.value} stalled ${stalledForMs}ms at its end, but auto-play next is off",
            )
            return
        }
        Diag.log(
            "advance",
            "${state.itemId.value} stalled ${stalledForMs}ms with only ${remainingMs}ms left " +
                "($diagnosis); treating it as ended",
        )
        Diag.log("advance", "${state.itemId.value} stall advance=${advance()}")
    }

    private fun resetRescues(item: MediaItemId) {
        rescues = 0
        rescuedItem = item
        rescuedAtMs = -1L
        // Genuine progress earns a clean slate, so an item that plays fine later in a long session
        // is not held to a verdict reached hours earlier.
        if (abandoned == item) abandoned = null
    }

    private companion object {
        /** Often enough to be responsive, rare enough to cost nothing when all is well. */
        const val CHECK_MS = 5_000L

        /**
         * A recovered pause worth one line. Below this it is an ordinary re-buffer and
         * saying so would cost more report buffer than it is worth.
         */
        const val NOTEWORTHY_MS = 10_000L

        /**
         * Long enough that an ordinary re-buffer is never mistaken for a stall, short enough
         * that the gap is not what the user notices. The observed stall was still frozen at
         * 46 seconds.
         */
        const val STALL_MS = 20_000L

        /**
         * Below this much buffered, call it starvation rather than a stuck player.
         *
         * Report 0.1.332 froze with 48ms and then 55ms in hand — reported as "STUCK", which reads
         * as "it has data and is not draining it" and points at the wrong fix entirely. A fifth of
         * a second is nothing on any stream this app plays.
         */
        const val STARVED_UNDER_MS = 200L

        /**
         * Replays before giving up on a stream. Two, because the first can be bad luck and a third
         * identical failure is information rather than a reason to try a fourth.
         */
        const val MAX_RESCUES = 2

        /**
         * Forward progress that proves a rescue worked, measured PAST where the rescue resumed.
         * Anything smaller and the replay's own seek counts as success.
         */
        const val PROGRESS_MS = 3_000L

        /**
         * How close to the duration counts as "this is the end". The observed stall was 7
         * seconds short; a whole item's tail is what a player fails to load, not a minute of
         * it.
         */
        const val END_MS = 15_000L
    }
}
