package com.dewijones92.totum.exsurge

import com.dewijones92.totum.common.Diag
import java.time.Instant

class SteadyWalkWatch(settings: ExsurgeSettings) {
    private var walk = newWalk(settings)
    private var offLogged = false

    val minutesDone: Int get() = walk.minutesDone

    fun reconfigure(next: ExsurgeSettings) {
        walk = newWalk(next)
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge steady walk settings changed to ${next.steadyWalkMinutes} minutes of " +
                "${next.steadyWalkStepsPerMinute} steps; the stretch in progress starts again",
        )
    }

    fun clear() = walk.clear()

    fun record(at: Instant, total: Long, settings: ExsurgeSettings, state: String): List<SteadyWalkEvent> {
        if (!settings.enabled) {
            if (!offLogged) {
                offLogged = true
                Diag.log(
                    ExsurgeController.TAG,
                    "dewidebug exsurge steady walk not tracked: the schedule is off, so a one-off clock " +
                        "earns nothing (state=$state)",
                )
            }
            return emptyList()
        }
        offLogged = false
        return walk.record(at, total).onEach { log(it, settings) }
    }

    fun describe(settings: ExsurgeSettings, tracked: Boolean): String =
        "$minutesDone of ${settings.steadyWalkMinutes} steady minutes " +
            "(at least ${settings.steadyWalkStepsPerMinute} steps a minute), tracked=$tracked"

    private fun log(event: SteadyWalkEvent, settings: ExsurgeSettings) {
        val needed = settings.steadyWalkStepsPerMinute
        val minutes = settings.steadyWalkMinutes
        val text = when (event) {
            is SteadyWalkEvent.Started ->
                "stretch started at ${event.at}: needs $minutes minutes of at least " +
                    "$needed steps each"
            is SteadyWalkEvent.MinuteDone ->
                "minute ${event.minute} of $minutes done with ${event.steps} steps (needs $needed)"
            is SteadyWalkEvent.Broken ->
                "stretch ended after ${event.minutesDone} minute(s): the next minute had " +
                    "${event.steps} steps against ${event.needed}"
            is SteadyWalkEvent.Completed ->
                "$minutes steady minutes since ${event.startedAt} with " +
                    "${event.steps} steps: counted as a finished break"
        }
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge steady walk: $text")
    }

    private companion object {
        fun newWalk(settings: ExsurgeSettings) =
            SteadyWalk(settings.steadyWalkStepsPerMinute, settings.steadyWalkMinutes)
    }
}
