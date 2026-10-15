package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object Recompositions {
    private val total = ConcurrentHashMap<String, AtomicLong>()
    private val sinceReport = ConcurrentHashMap<String, AtomicLong>()

    fun count(screen: String) {
        total.getOrPut(screen) { AtomicLong() }.incrementAndGet()
        sinceReport.getOrPut(screen) { AtomicLong() }.incrementAndGet()
    }

    fun of(screen: String): Long = total[screen]?.get() ?: 0

    fun takeReport(): String? {
        val counts = sinceReport.entries
            .map { (screen, count) -> screen to count.getAndSet(0) }
            .filter { it.second > 0 }
            .sortedBy { it.first }
        return counts.takeIf { it.isNotEmpty() }?.joinToString(" ") { (screen, n) -> "$screen=$n" }
    }
}

@Composable
@NonRestartableComposable
fun CountRecompositions(screen: String) {
    SideEffect { Recompositions.count(screen) }
}
