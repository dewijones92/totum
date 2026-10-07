package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Vitals
import java.time.LocalTime
import java.time.format.DateTimeFormatter

internal class RecentRoutes(
    private val kept: Int = KEPT,
    private val now: () -> LocalTime = LocalTime::now,
) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun remember(routeLine: String) {
        lines.addLast("${now().format(CLOCK)} $routeLine")
        while (lines.size > kept) lines.removeFirst()
        Vitals.set(VITAL, lines.joinToString(" || "))
    }

    companion object {
        const val VITAL = "playback.recentRoutes"
        private const val KEPT = 6
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
