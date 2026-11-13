package com.dewijones92.totum.cli.serve.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.TargetSleep
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.playback.FadeRamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class SleepPlayback(
    val itemId: String?,
    val positionMs: Long?,
    val durationMs: Long?,
    val live: Boolean,
)

internal class DeckSleep(
    private val label: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val player: TargetPlayer,
    private val volume: TargetVolume?,
    private val playback: () -> SleepPlayback,
    private val pause: suspend () -> Unit,
) {
    private class Armed(
        val kind: SleepKind,
        val deadlineMs: Long,
        val itemIds: List<String>,
        val title: String?,
        val ramp: FadeRamp,
    ) {
        var job: Job? = null
        var base: Int? = null
        var lastGain = 1f
        var lastSet: Int? = null
        var noFadeLogged = false
    }

    @Volatile
    private var armed: Armed? = null

    suspend fun arm(ask: TargetSleepAsk) {
        release("replaced by a new timer")
        val fade = ask.fadeSeconds.coerceIn(0, MAX_FADE_SECONDS).seconds
        val total = ask.seconds.coerceIn(1, MAX_SLEEP_SECONDS).seconds
        val timed = ask.kind == SleepKind.AFTER_TIME
        val sleep = Armed(
            kind = ask.kind,
            deadlineMs = if (timed) clock() + total.inWholeMilliseconds else 0,
            itemIds = ask.itemIds,
            title = ask.title,
            ramp = FadeRamp(if (timed) minOf(fade, total) else fade),
        )
        armed = sleep
        Diag.log(
            "sleep",
            "dewidebug sleep timer armed on $label: ${ask.kind} " +
                (if (timed) "in $total" else "after ${ask.itemIds.size} item(s) ending ${ask.itemIds.lastOrNull()}") +
                ", fade ${sleep.ramp.length}",
        )
        sleep.job = scope.launch { if (timed) countDown(sleep) else watchItems(sleep) }
    }

    suspend fun release(why: String): Boolean {
        val sleep = armed ?: return false
        armed = null
        sleep.job?.cancel()
        restore(sleep, why)
        Diag.log("sleep", "dewidebug sleep timer on $label released: $why")
        return true
    }

    fun endsWith(itemId: String): Boolean =
        armed?.takeIf { it.kind == SleepKind.AFTER_ITEMS }?.itemIds?.lastOrNull() == itemId

    fun leftItems(itemId: String): Boolean =
        armed?.takeIf { it.kind == SleepKind.AFTER_ITEMS }?.let { itemId !in it.itemIds } == true

    suspend fun personSetVolume(wanted: Int): Boolean {
        val sleep = armed ?: return false
        val base = sleep.base ?: return false
        if (!sleep.ramp.faded) return false
        val kept = minOf(base, wanted)
        Diag.log(
            "sleep",
            "dewidebug sleep fade on $label: the person set the volume to $wanted during the fade; " +
                "the fade now runs from $kept (never above the $base it began at)",
        )
        sleep.base = kept
        sleep.lastSet = null
        apply(sleep)
        return true
    }

    fun state(): TargetSleep? {
        val sleep = armed ?: return null
        val current = playback().itemId
        val at = sleep.itemIds.indexOf(current)
        return TargetSleep(
            kind = sleep.kind,
            remainingMs = if (sleep.kind == SleepKind.AFTER_TIME) {
                (sleep.deadlineMs - clock()).coerceAtLeast(0)
            } else {
                null
            },
            itemsLeft = if (sleep.kind == SleepKind.AFTER_ITEMS) sleep.itemIds.size - at.coerceAtLeast(0) else 0,
            title = sleep.title,
            fadeSeconds = sleep.ramp.length.inWholeSeconds.toInt(),
            fading = sleep.ramp.faded,
        )
    }

    private suspend fun countDown(sleep: Armed) {
        while (true) {
            val remaining = (sleep.deadlineMs - clock()).coerceAtLeast(0).milliseconds
            step(sleep, remaining)
            if (remaining <= Duration.ZERO) break
            delay(
                if (sleep.ramp.due(remaining)) {
                    FADE_TICK
                } else {
                    (remaining - sleep.ramp.length).coerceIn(FADE_TICK, TICK)
                }
            )
        }
        fire(sleep)
    }

    private suspend fun watchItems(sleep: Armed) {
        while (true) {
            val remaining = remainingInLast(sleep)
            if (remaining != null) step(sleep, remaining)
            delay(if (remaining != null && sleep.ramp.due(remaining)) FADE_TICK else TICK)
        }
    }

    private fun remainingInLast(sleep: Armed): Duration? {
        val facts = playback()
        if (facts.itemId != sleep.itemIds.lastOrNull()) return null
        val duration = facts.durationMs?.takeIf { it > 0 && !facts.live }
        val position = facts.positionMs
        if (duration == null || position == null) {
            noFade(sleep, "${facts.itemId} is live or has no known length")
            return null
        }
        return (duration - position).coerceAtLeast(0).milliseconds
    }

    private suspend fun step(sleep: Armed, remaining: Duration) {
        if (armed !== sleep) return
        if (!sleep.ramp.faded && sleep.ramp.due(remaining)) {
            volume?.refresh()
            sleep.base = if (volume != null) volume.percent else player.fadeBasePercent
            if (sleep.base == null) return noFade(sleep, "its volume is not known")
        }
        val base = sleep.base ?: return
        val gain = sleep.ramp.next(remaining, "$label's volume (from $base%)") ?: return
        sleep.lastGain = gain
        apply(sleep)
    }

    private suspend fun apply(sleep: Armed) {
        val base = sleep.base ?: return
        val level = (base * sleep.lastGain).roundToInt().coerceIn(0, base)
        if (level == sleep.lastSet) return
        sleep.lastSet = level
        setLevel(level)
    }

    private fun noFade(sleep: Armed, why: String) {
        if (sleep.noFadeLogged) return
        sleep.noFadeLogged = true
        Diag.log("sleep", "dewidebug sleep timer on $label: no fade, $why; playback still stops")
    }

    private suspend fun fire(sleep: Armed) {
        Diag.log("sleep", "dewidebug sleep timer on $label fires: the countdown ended, pausing")
        try {
            pause()
            delay(RESTORE_AFTER_PAUSE)
        } finally {
            if (armed === sleep) armed = null
            withContext(NonCancellable) { restore(sleep, "the countdown ended and playback was paused") }
        }
    }

    private suspend fun restore(sleep: Armed, why: String) {
        if (!sleep.ramp.end(why)) return
        val base = sleep.base ?: return
        Diag.log("sleep", "dewidebug sleep timer on $label: volume back to $base% after the fade")
        setLevel(base)
    }

    private suspend fun setLevel(percent: Int) {
        if (volume != null) volume.set(percent) else player.volume(percent)
    }

    private companion object {
        val TICK: Duration = 1.seconds
        val FADE_TICK: Duration = 250.milliseconds
        val RESTORE_AFTER_PAUSE: Duration = 1.seconds
        const val MAX_FADE_SECONDS = 60
        const val MAX_SLEEP_SECONDS = 12 * 60 * 60
    }
}
