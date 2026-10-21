package com.dewijones92.totum.playback.cast

import java.util.TreeMap

internal class CastTimeline(private val cuts: Boolean, private val sourceDurationUs: () -> Long) {

    private val anchors = TreeMap<Long, Long>()

    @Volatile
    var endTvUs: Long? = null
        private set

    val cutsSilences: Boolean get() = cuts

    @Synchronized
    fun anchor(tvUs: Long, sourceUs: Long) {
        anchors[tvUs] = sourceUs
    }

    @Synchronized
    fun ended(tvUs: Long) {
        endTvUs = tvUs
    }

    @Synchronized
    fun sourceFor(tvUs: Long): Long {
        if (!cuts) return tvUs
        val floor = anchors.floorEntry(tvUs) ?: return tvUs
        val past = tvUs - floor.key
        val source = floor.value + if (floor.key == anchors.lastKey()) (past / keptRatio()).toLong() else past
        return sourceDurationUs().takeIf { it > 0 }?.let { source.coerceAtMost(it) } ?: source
    }

    @Synchronized
    fun tvFor(sourceUs: Long): Long {
        if (!cuts) return sourceUs
        val before = anchors.entries.lastOrNull { it.value <= sourceUs } ?: return sourceUs
        val past = sourceUs - before.value
        return before.key + if (before.key == anchors.lastKey()) (past * keptRatio()).toLong() else past
    }

    private fun keptRatio(): Double {
        if (anchors.size < 2) return 1.0
        val first = anchors.firstEntry()
        val last = anchors.lastEntry()
        val ratio = (last.key - first.key).toDouble() / (last.value - first.value).coerceAtLeast(1)
        return ratio.coerceIn(MIN_KEPT, 1.0)
    }

    private companion object {
        const val MIN_KEPT = 0.3
    }
}
