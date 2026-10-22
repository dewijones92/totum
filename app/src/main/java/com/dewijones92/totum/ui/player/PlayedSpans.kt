package com.dewijones92.totum.ui.player

internal class PlayedSpans(private val continuousMs: Long = CONTINUOUS_MS) {

    private val held = mutableListOf<LongRange>()
    private var last: Long? = null

    val spans: List<LongRange> get() = merged()

    fun at(positionMs: Long) {
        val step = last?.let { positionMs - it }
        last = positionMs
        if (step != null && step in 0..continuousMs && held.isNotEmpty()) {
            val open = held.last()
            held[held.lastIndex] = open.first..maxOf(open.last, positionMs)
        } else {
            held += positionMs..positionMs
        }
    }

    fun reset() {
        held.clear()
        last = null
    }

    private fun merged(): List<LongRange> = held.filter { it.last > it.first }.sortedBy { it.first }
        .fold(mutableListOf()) { out, span ->
            val tail = out.lastOrNull()
            if (tail != null && span.first <= tail.last) {
                out[out.lastIndex] = tail.first..maxOf(tail.last, span.last)
            } else {
                out += span
            }
            out
        }

    private companion object {
        const val CONTINUOUS_MS = 5_000L
    }
}
