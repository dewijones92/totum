package com.dewijones92.totum.navigation

import android.content.Context
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant

object TabNews {
    private val WINDOW: Duration = Duration.ofDays(60)

    fun recentIds(episodes: List<MediaItem>, now: Instant): Set<String> =
        episodes.filter { it.counts(now) }.map { it.id.value }.toSet()

    fun newEpisodes(episodes: List<MediaItem>, seenIds: Set<String>?, now: Instant): Int {
        if (seenIds == null) return 0
        return episodes.count { it.counts(now) && it.id.value !in seenIds }
    }

    fun badges(counts: Map<TopLevelDestination, Int>, selected: TopLevelDestination): Map<TopLevelDestination, Int> =
        counts.filter { (tab, count) -> tab != selected && count > 0 }

    private fun MediaItem.counts(now: Instant): Boolean =
        contentKind != MediaContentKind.AUDIOBOOK && publishedAt?.isAfter(now.minus(WINDOW)) == true
}

class TabSeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("totum_tab_seen", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val seen: StateFlow<Map<TopLevelDestination, Set<String>>> = state.asStateFlow()

    fun markSeen(tab: TopLevelDestination, ids: Set<String>, why: String) {
        val before = state.value[tab]
        prefs.edit { putStringSet(key(tab), ids) }
        state.value = state.value + (tab to ids)
        val cleared = before?.let { (ids - it).size } ?: 0
        Diag.log("nav", "${tab.name} looked at ($why); ${ids.size} recent item(s) now seen, $cleared newly")
    }

    private fun read(): Map<TopLevelDestination, Set<String>> = TopLevelDestination.entries
        .mapNotNull { tab -> prefs.getStringSet(key(tab), null)?.let { tab to it.toSet() } }
        .toMap()

    private fun key(tab: TopLevelDestination) = "${tab.name}:ids"
}
