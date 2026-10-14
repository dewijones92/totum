package com.dewijones92.totum.exsurge

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

public class FlatStillness(
    private val tiltCosine: Float = TILT_COSINE,
    private val wobble: Float = WOBBLE,
) {
    private var flatSinceMs: Long? = null
    private var last: Triple<Float, Float, Float>? = null

    public fun feed(x: Float, y: Float, z: Float, atMs: Long): Long? {
        val norm = sqrt(x * x + y * y + z * z)
        val flat = norm > 0f && abs(z) / norm >= tiltCosine && abs(norm - GRAVITY) <= wobble
        val previous = last
        val still = previous == null || listOf(x - previous.first, y - previous.second, z - previous.third)
            .all { abs(it) <= wobble }
        last = Triple(x, y, z)
        if (!flat || !still) {
            flatSinceMs = null
            return null
        }
        val since = flatSinceMs ?: atMs.also { flatSinceMs = it }
        return atMs - since
    }

    public fun reset() {
        flatSinceMs = null
        last = null
    }

    public companion object {
        public const val GRAVITY: Float = 9.81f
        public const val TILT_COSINE: Float = 0.966f
        public const val WOBBLE: Float = 0.6f
    }
}

public sealed interface FlatNudge {
    public data object Nudge : FlatNudge

    public data class Not(val why: String) : FlatNudge
}

public fun flatNudgeFor(
    settings: ExsurgeSettings,
    flatForMs: Long?,
    at: Instant,
    zone: ZoneId,
    lastNudgeAt: Instant?,
    mutedToday: Boolean = false,
): FlatNudge = when {
    settings.enabled -> FlatNudge.Not("Exsurge is on")
    !settings.flatNudge -> FlatNudge.Not("the nudge is switched off")
    mutedToday -> FlatNudge.Not("not today, as asked")
    !settings.isActiveAt(at, zone) -> FlatNudge.Not("outside active hours")
    flatForMs == null -> FlatNudge.Not("not lying flat and still")
    flatForMs < Duration.ofMinutes(settings.flatNudgeMinutes.toLong()).toMillis() ->
        FlatNudge.Not("flat for ${flatForMs / MILLIS_PER_SECOND}s of ${settings.flatNudgeMinutes}min")
    lastNudgeAt != null && Duration.between(lastNudgeAt, at) < NUDGE_GAP ->
        FlatNudge.Not("nudged ${Duration.between(lastNudgeAt, at).toMinutes()}min ago")
    else -> FlatNudge.Nudge
}

private val NUDGE_GAP: Duration = Duration.ofHours(1)
private const val MILLIS_PER_SECOND = 1_000L
