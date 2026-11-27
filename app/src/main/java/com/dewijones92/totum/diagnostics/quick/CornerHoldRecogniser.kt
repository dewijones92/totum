package com.dewijones92.totum.diagnostics.quick

import kotlin.math.hypot

internal class CornerZone(private val left: Float, private val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x >= left && y in 0f..bottom

    companion object {
        fun topRight(widthPx: Float, sizePx: Float, topInsetPx: Float = 0f): CornerZone =
            CornerZone(left = widthPx - sizePx, bottom = topInsetPx + sizePx)
    }
}

internal class CornerHoldRecogniser(
    private val inZone: (Float, Float) -> Boolean,
    private val holdMs: Long = HOLD_MS,
    private val slopPx: Float,
) {
    sealed interface Verdict {
        data object Idle : Verdict
        data class Holding(val remainingMs: Long) : Verdict
        data object Fired : Verdict
        data class Abandoned(val why: String, val heldMs: Long) : Verdict
    }

    private val down = mutableSetOf<Long>()
    private var tracked: Long? = null
    private var startX = 0f
    private var startY = 0f
    private var startAtMs = 0L
    private var fired = false

    fun owns(pointer: Long): Boolean = tracked == pointer && fired

    fun dueAtMs(): Long? = if (tracked != null && !fired) startAtMs + holdMs else null

    fun remainingMs(atMs: Long): Long = (holdMs - (atMs - startAtMs)).coerceAtLeast(0)

    fun down(pointer: Long, x: Float, y: Float, atMs: Long): Verdict {
        down += pointer
        if (tracked != null) return if (fired) Verdict.Idle else abandon("a second finger came down", atMs)
        if (down.size > 1 || !inZone(x, y)) return Verdict.Idle
        tracked = pointer
        startX = x
        startY = y
        startAtMs = atMs
        fired = false
        return Verdict.Holding(holdMs)
    }

    fun move(pointer: Long, x: Float, y: Float, atMs: Long): Verdict {
        if (tracked != pointer || fired) return Verdict.Idle
        val drifted = hypot(x - startX, y - startY)
        return when {
            !inZone(x, y) -> abandon("the finger left the corner", atMs)
            drifted > slopPx -> abandon("the finger moved ${drifted.toInt()} px", atMs)
            else -> Verdict.Holding(remainingMs(atMs))
        }
    }

    fun tick(atMs: Long): Verdict {
        if (tracked == null || fired) return Verdict.Idle
        if (atMs - startAtMs < holdMs) return Verdict.Holding(remainingMs(atMs))
        fired = true
        return Verdict.Fired
    }

    fun up(pointer: Long, atMs: Long): Verdict {
        down -= pointer
        if (tracked != pointer) return Verdict.Idle
        if (fired) return release()
        if (atMs - startAtMs >= holdMs) {
            fired = true
            return Verdict.Fired
        }
        return abandon("released early", atMs)
    }

    fun cancelAll(why: String, atMs: Long): Verdict {
        down.clear()
        return if (tracked == null) Verdict.Idle else abandon(why, atMs)
    }

    private fun release(): Verdict {
        tracked = null
        fired = false
        return Verdict.Idle
    }

    private fun abandon(why: String, atMs: Long): Verdict {
        tracked = null
        fired = false
        return Verdict.Abandoned(why, heldMs = atMs - startAtMs)
    }

    companion object {
        const val HOLD_MS = 1_500L
    }
}
