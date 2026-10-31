package com.dewijones92.totum.navigation

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TabNewsTest {
    private val now = Instant.parse("2026-10-31T12:00:00Z")

    private fun episode(id: String, published: String?, kind: MediaContentKind = MediaContentKind.STANDARD) =
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("feed"),
            title = id,
            publishedAt = published?.let(Instant::parse),
            duration = null,
            contentKind = kind,
        )

    @Test
    fun `counts the episodes that arrived since the tab was last looked at, whenever they were published`() {
        val known = listOf(episode("old", "2026-10-30T08:00:00Z"))
        val seen = TabNews.recentIds(known, now)
        val later = known + listOf(
            episode("published-before-the-look-but-fetched-after", "2026-10-31T08:00:00Z"),
            episode("newer", "2026-10-31T11:00:00Z"),
            episode("undated", null),
            episode("chapter", "2026-10-31T11:00:00Z", MediaContentKind.AUDIOBOOK),
            episode("from-the-archive", "2024-01-01T00:00:00Z"),
        )

        assertEquals(2, TabNews.newEpisodes(later, seen, now))
    }

    @Test
    fun `a tab never looked at shows no badge, rather than every episode ever`() {
        assertEquals(0, TabNews.newEpisodes(listOf(episode("new", "2026-10-31T10:00:00Z")), null, now))
    }

    @Test
    fun `the tab you are on wears no badge`() {
        val counts = mapOf(TopLevelDestination.Podcasts to 3, TopLevelDestination.Library to 2)

        assertEquals(
            mapOf(TopLevelDestination.Library to 2),
            TabNews.badges(counts, selected = TopLevelDestination.Podcasts),
        )
        assertEquals(
            mapOf(TopLevelDestination.Podcasts to 3, TopLevelDestination.Library to 2),
            TabNews.badges(counts + (TopLevelDestination.Queue to 0), selected = TopLevelDestination.Videos),
        )
    }
}
