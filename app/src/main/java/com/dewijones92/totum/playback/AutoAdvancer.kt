package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Starts the next item when one finishes — off the UI's lifecycle, so it works with the
 * screen off.
 *
 * This used to live in a composable's `LaunchedEffect`, reading playback state through
 * `collectAsStateWithLifecycle()`. That collection **stops when the activity is stopped**, so
 * with the phone in a pocket the composition never saw the end and the decision was simply
 * never made. A real report caught it exactly: a video ended at 14:39:09 with sixty items
 * queued, and the advance decision was not reached until 14:46:13 — seven minutes later, when
 * the app came back to the foreground. The 30-second activity snapshots kept arriving
 * throughout, because they run on a plain coroutine; only the advance was frozen.
 *
 * The same class of bug as playback work on `rememberCoroutineScope()`: logic that must
 * outlive the UI, hosted by the UI. Anything the user expects to keep happening in their
 * pocket belongs on an application-lifetime scope.
 *
 * **It has no memory, and that is the design.** It used to watch `PlaybackState` — a level
 * signal that re-emits on every position tick and drops values equal to the last — so it had to
 * reconstruct the edge itself: dedupe the transition, ignore the first state as a baseline, and
 * remember which ends it had already acted on. That last field is what broke autoplay on
 * 2026-08-01: it held one item id for the life of the process, so an item played a second time
 * was refused citing an end three hours old, and the queue stopped. Reading
 * [PlaybackEvent.Ended] instead removes the reconstruction and every guard that went with it —
 * an event arrives once, when it happens, so acting on each one is correct with nothing
 * remembered and nothing left to go stale. See [PlaybackEvent].
 *
 * @param whenQueueEmpty tried when the queue has nothing playable left — the related-video
 *   fallback. Suspending and app-scoped, so it is not tied to a screen either.
 */
internal class AutoAdvancer(
    private val events: Flow<PlaybackEvent>,
    private val advance: suspend () -> Boolean,
    private val whenQueueEmpty: suspend () -> Unit,
    private val isEnabled: () -> Boolean,
    private val scope: CoroutineScope,
) {
    fun start() {
        // Said out loud so "nothing ended" and "the advancer was not running" stop looking
        // identical in a report. Dewi asked whether a report showed auto-play-next failing;
        // it showed no end-of-video at all, and without this line there is no way to tell
        // that apart from a collector that never started or was cancelled.
        Diag.log("advance", "watching for end of playback (auto-play next is ${onOrOff()})")
        scope.launch {
            events.collect { event ->
                when (event) {
                    is PlaybackEvent.Ended -> advancePast(event)
                    is PlaybackEvent.CrossedOver -> Diag.log(
                        "advance",
                        "${event.fromItemId.value} did not end here: the player crossed to ${event.itemId.value}",
                    )
                }
            }
        }
    }

    private fun onOrOff(): String = if (isEnabled()) "on" else "off"

    private suspend fun advancePast(ended: PlaybackEvent.Ended) {
        val id = ended.itemId.value
        if (!isEnabled()) {
            // The one refusal left, and it still says so: an item ending with nothing happening
            // is otherwise indistinguishable from the advancer being dead.
            Diag.log("advance", "not advancing past $id: auto-play next is off")
            return
        }
        val advanced = advance()
        Diag.log("advance", "$id ended at ${ended.atMs}ms -> queue advance=$advanced")
        if (!advanced) {
            Diag.log("advance", "queue had nothing playable; trying a related video")
            whenQueueEmpty()
        }
    }
}
