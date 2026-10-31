package com.dewijones92.totum.navigation

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

object TabNews {
    fun newEpisodes(episodes: List<MediaItem>, seenUpTo: Instant?): Int {
        if (seenUpTo == null) return 0
        return episodes.count { episode ->
            episode.contentKind != MediaContentKind.AUDIOBOOK && episode.publishedAt?.isAfter(seenUpTo) == true
        }
    }

    fun badges(counts: Map<TopLevelDestination, Int>, selected: TopLevelDestination): Map<TopLevelDestination, Int> =
        counts.filter { (tab, count) -> tab != selected && count > 0 }
}

class TabSeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("totum_tab_seen", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val seen: StateFlow<Map<TopLevelDestination, Instant>> = state.asStateFlow()

    fun markSeen(tab: TopLevelDestination, at: Instant, why: String) {
        val before = state.value[tab]
        prefs.edit { putLong(tab.name, at.toEpochMilli()) }
        state.value = state.value + (tab to at)
        Diag.log("nav", "${tab.name} looked at ($why); new since ${before ?: "never"} is cleared")
    }

    private fun read(): Map<TopLevelDestination, Instant> = TopLevelDestination.entries
        .mapNotNull { tab -> prefs.getLong(tab.name, 0L).takeIf { it > 0 }?.let { tab to Instant.ofEpochMilli(it) } }
        .toMap()
}
