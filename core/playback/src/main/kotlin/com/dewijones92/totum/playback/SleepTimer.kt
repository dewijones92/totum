package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
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
    private val fade: () -> Duration = { Duration.ZERO },
) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    public val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private val _cancellations = MutableSharedFlow<SleepTimerCancelled>(extraBufferCapacity = 1)
    public val cancellations: SharedFlow<SleepTimerCancelled> = _cancellations

    private var armedBy: Any? = null
    private var castBase: Float? = null
    private var noFadeLogged = false

    private var job: Job? = null
    private var fadedTo: Float = FULL

    /** Starts (or restarts) the timer for [duration]. */
    public fun start(duration: Duration) {
        release()
        job = scope.launch {
            var remaining = duration
            val fadeLength = fade()
            _state.value = SleepTimerState.Running(remaining)
            while (isActive && remaining > Duration.ZERO) {
                val step = if (fadeLength > Duration.ZERO && remaining <= fadeLength) FADE_TICK else TICK
                delay(step)
                remaining -= step
                _state.value = SleepTimerState.Running(remaining.coerceAtLeast(Duration.ZERO))
                fadeTo(remaining, fadeLength)
            }
            if (controller.state.value?.isPlaying == true) controller.togglePlayPause()
            restoreVolume("the countdown ended")
            _state.value = SleepTimerState.Off
        }
    }

    /**
     * Stops when the current item finishes, however long that is.
     *
     * Ends on the first change away from this item, which covers the user skipping on: having
     * asked to stop after this one, being carried into another would be the wrong answer either way.
     */
    public fun stopAfterCurrentItem(owner: Any? = null) {
        val current = controller.state.value?.itemId ?: return
        stopAfterLastOf(listOf(current), owner) { SleepTimerState.AfterCurrentItem }
    }

    /** Stops when the last of [itemIds], the rest of an album or playlist in play order, finishes. */
    public fun stopAtEndOf(itemIds: List<MediaItemId>, title: String) {
        if (itemIds.isEmpty()) return
        stopAfterLastOf(itemIds, null) { playing -> SleepTimerState.AtEndOf(title, itemIds.size - itemIds.indexOf(playing)) }
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
        Diag.log("sleep", "dewidebug sleep timer fires: $itemId ended and the timer was set to stop after it")
        disarm()
        return true
    }

    private var stopsAfter: MediaItemId? = null

    private fun stopAfterLastOf(
        itemIds: List<MediaItemId>,
        owner: Any?,
        describe: (MediaItemId) -> SleepTimerState,
    ) {
        release()
        stopsAfter = itemIds.last()
        armedBy = owner
        val fadeLength = fade()
        job = scope.launch {
            controller.state.collect { state ->
                if (state == null || state.itemId !in itemIds) {
                    if (state?.isPlaying == true) controller.togglePlayPause()
                    stopsAfter = null
                    armedBy = null
                    restoreVolume("playback moved off the items the timer was waiting on")
                    _state.value = SleepTimerState.Off
                    job?.cancel()
                } else {
                    _state.value = describe(state.itemId)
                    if (state.itemId == itemIds.last()) fadeToEndOf(state, fadeLength)
                }
            }
        }
    }

    private fun fadeToEndOf(state: PlaybackState, fadeLength: Duration) {
        val duration = state.durationMs?.takeIf { it > 0 && !state.live }
        if (duration == null) {
            noteNoFade("no fade: ${state.itemId} is live or has no known length")
            return
        }
        val speed = state.speed.takeIf { it > 0f } ?: 1f
        fadeTo(((duration - state.positionMs) / speed).toLong().milliseconds, fadeLength)
    }

    private fun noteNoFade(why: String) {
        if (noFadeLogged) return
        noFadeLogged = true
        Diag.log("sleep", "dewidebug sleep timer $why; playback still stops")
    }

    private fun fadeTo(remaining: Duration, fadeLength: Duration) {
        if (fadeLength <= Duration.ZERO) return
        val level = (remaining / fadeLength).toFloat().coerceIn(0f, FULL)
        if (level >= FULL && fadedTo >= FULL) return
        if (abs(level - fadedTo) < MIN_FADE_STEP && level > 0f) return
        if (fadedTo >= FULL && !chooseOutput()) return
        if (fadedTo >= FULL) {
            val on = castBase?.let { "the TV volume (from ${percent(it)}%)" } ?: "the player"
            Diag.log("sleep", "dewidebug sleep timer fade begins on $on: remaining=$remaining of a $fadeLength fade")
        }
        if (level == 0f) Diag.log("sleep", "dewidebug sleep timer fade reached silence")
        fadedTo = level
        castBase?.let { controller.setCastVolume(it * level) } ?: controller.setFadeLevel(level)
    }

    private fun chooseOutput(): Boolean {
        val state = controller.state.value
        if (state?.onCastDevice != true) return true
        val volume = state.castVolume
        if (volume == null) {
            noteNoFade("no fade: casting to ${state.castDeviceName ?: "a device"}, which offers no volume to ramp")
            return false
        }
        castBase = volume.fraction
        return true
    }

    private fun percent(fraction: Float): Int = (fraction * PERCENT).roundToInt()

    private fun restoreVolume(why: String) {
        if (fadedTo >= FULL) return
        val base = castBase
        val output = base?.let { "TV volume back to ${percent(it)}%" } ?: "player volume"
        Diag.log("sleep", "dewidebug sleep timer volume back to full after a fade to $fadedTo ($output): $why")
        fadedTo = FULL
        castBase = null
        base?.let { controller.setCastVolume(it) } ?: controller.setFadeLevel(FULL)
    }

    private fun release() {
        job?.cancel()
        job = null
        stopsAfter = null
        armedBy = null
        noFadeLogged = false
        restoreVolume("the timer was released")
    }

    private fun disarm() {
        release()
        _state.value = SleepTimerState.Off
    }

    /** Cancels a running timer, leaving playback alone. */
    public fun cancel() {
        val wasArmed = _state.value != SleepTimerState.Off
        val owner = armedBy
        disarm()
        if (wasArmed) {
            Diag.log("sleep", "dewidebug sleep timer cancelled by the person (armed by ${owner ?: "the person"})")
            _cancellations.tryEmit(SleepTimerCancelled(owner))
        }
    }

    private companion object {
        val TICK: Duration = 1.seconds
        val FADE_TICK: Duration = 250.milliseconds
        const val FULL = 1f
        const val MIN_FADE_STEP = 0.02f
        const val PERCENT = 100
    }
}

public data class SleepTimerCancelled(val owner: Any?)

/** Whether a sleep timer is running, and how long is left. */
public sealed interface SleepTimerState {
    public data object Off : SleepTimerState
    public data class Running(val remaining: Duration) : SleepTimerState

    /** Armed with no countdown to show — it ends when the item does. */
    public data object AfterCurrentItem : SleepTimerState

    /** Ends with the last song of [title]; [songsLeft] counts the one playing. */
    public data class AtEndOf(val title: String, val songsLeft: Int) : SleepTimerState
}
