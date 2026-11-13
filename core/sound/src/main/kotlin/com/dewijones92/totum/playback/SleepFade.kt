package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import kotlin.math.abs
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

public object SleepFade {
    public val DEFAULT_LENGTH: Duration = 20.seconds

    public fun gain(heardLevel: Float): Float =
        heardLevel.coerceIn(SILENT, FULL).toDouble().pow(1.0 / PERCEIVED_PER_AMPLITUDE).toFloat()

    public fun heardLevel(remaining: Duration, length: Duration): Float =
        if (length <= Duration.ZERO) FULL else (remaining / length).toFloat().coerceIn(SILENT, FULL)

    internal const val FULL = 1f
    internal const val SILENT = 0f
    private const val PERCEIVED_PER_AMPLITUDE = 0.6
}

public class FadeRamp(public val length: Duration) {
    private var heard = SleepFade.FULL
    private var steps = 0
    private var on = ""

    public val faded: Boolean get() = heard < SleepFade.FULL

    public val stepCount: Int get() = steps

    public fun due(remaining: Duration): Boolean = length > Duration.ZERO && remaining < length

    public fun next(remaining: Duration, on: String): Float? {
        if (length <= Duration.ZERO) return null
        val wanted = SleepFade.heardLevel(remaining, length)
        if (wanted >= SleepFade.FULL && !faded) return null
        if (abs(wanted - heard) < MIN_STEP && wanted > SleepFade.SILENT) return null
        if (!faded) {
            this.on = on
            Diag.log(
                "sleep",
                "dewidebug sleep fade begins on $on: $remaining to go of a $length fade, " +
                    "steady in how loud it sounds, never above where it started",
            )
        }
        if (wanted == SleepFade.SILENT) Diag.log("sleep", "dewidebug sleep fade reached silence on $on")
        heard = wanted
        steps++
        return SleepFade.gain(wanted)
    }

    public fun end(why: String): Boolean {
        if (!faded) return false
        Diag.log(
            "sleep",
            "dewidebug sleep fade ended on $on after $steps step(s), heard level ${"%.2f".format(heard)}: $why; " +
                "the volume goes back to where it started",
        )
        heard = SleepFade.FULL
        steps = 0
        return true
    }

    private companion object {
        const val MIN_STEP = 0.01f
    }
}
