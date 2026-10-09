package com.dewijones92.totum.playback

import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pauses playback after a chosen delay — the classic "stop after N minutes"
 * sleep timer. Counts down once a second so the UI can show the time left, and
 * only pauses if something is still playing when it fires. Works for both
 * pillars since it drives the one [PlaybackController].
 *
 * Also stops **at the end of the current item** ([stopAfterCurrentItem]), which is what
 * you actually want falling asleep to a podcast: a fixed 30 minutes either cuts the
 * episode off mid-sentence or runs on into the next one.
 */
public class SleepTimer(
    private val controller: PlaybackController,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    public val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null

    /** Starts (or restarts) the timer for [duration]. */
    public fun start(duration: Duration) {
        job?.cancel()
        stopsAfter = null
        job = scope.launch {
            var remaining = duration
            _state.value = SleepTimerState.Running(remaining)
            while (isActive && remaining > Duration.ZERO) {
                delay(TICK)
                remaining -= TICK
                _state.value = SleepTimerState.Running(remaining.coerceAtLeast(Duration.ZERO))
            }
            if (controller.state.value?.isPlaying == true) controller.togglePlayPause()
            _state.value = SleepTimerState.Off
        }
    }

    /**
     * Stops when the current item finishes, however long that is.
     *
     * Ends on the first change away from this item, which covers the user skipping on: having
     * asked to stop after this one, being carried into another would be the wrong answer either way.
     */
    public fun stopAfterCurrentItem() {
        val current = controller.state.value?.itemId ?: return
        stopAfterLastOf(listOf(current)) { SleepTimerState.AfterCurrentItem }
    }

    /** Stops when the last of [itemIds], the rest of an album or playlist in play order, finishes. */
    public fun stopAtEndOf(itemIds: List<MediaItemId>, title: String) {
        if (itemIds.isEmpty()) return
        stopAfterLastOf(itemIds) { playing -> SleepTimerState.AtEndOf(title, itemIds.size - itemIds.indexOf(playing)) }
    }

    /** The item playback stops after, while a timer of that kind is armed. */
    public val stopsAfterItem: MediaItemId? get() = stopsAfter

    /**
     * Asked by the queue as [itemId] ends: true when the timer stops here, and it fires.
     *
     * The queue asks rather than the timer watching for the end, because the end arrives with
     * nothing playing: a timer that disarmed on it paused nothing and the queue went on to the next
     * item anyway.
     */
    public fun firesAfter(itemId: MediaItemId): Boolean {
        if (stopsAfter != itemId) return false
        cancel()
        return true
    }

    private var stopsAfter: MediaItemId? = null

    private fun stopAfterLastOf(itemIds: List<MediaItemId>, describe: (MediaItemId) -> SleepTimerState) {
        job?.cancel()
        stopsAfter = itemIds.last()
        job = scope.launch {
            controller.state.collect { state ->
                if (state == null || state.itemId !in itemIds) {
                    if (state?.isPlaying == true) controller.togglePlayPause()
                    stopsAfter = null
                    _state.value = SleepTimerState.Off
                    job?.cancel()
                } else {
                    _state.value = describe(state.itemId)
                }
            }
        }
    }

    /** Cancels a running timer, leaving playback alone. */
    public fun cancel() {
        job?.cancel()
        job = null
        stopsAfter = null
        _state.value = SleepTimerState.Off
    }

    private companion object {
        val TICK: Duration = 1.seconds
    }
}

/** Whether a sleep timer is running, and how long is left. */
public sealed interface SleepTimerState {
    public data object Off : SleepTimerState
    public data class Running(val remaining: Duration) : SleepTimerState

    /** Armed with no countdown to show — it ends when the item does. */
    public data object AfterCurrentItem : SleepTimerState

    /** Ends with the last song of [title]; [songsLeft] counts the one playing. */
    public data class AtEndOf(val title: String, val songsLeft: Int) : SleepTimerState
}
