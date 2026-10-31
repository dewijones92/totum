package com.dewijones92.totum.navigation

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TabNewsTest {
    private val looked = Instant.parse("2026-10-31T09:00:00Z")

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
    fun `counts the episodes published since the tab was last looked at`() {
        val episodes = listOf(
            episode("old", "2026-10-30T08:00:00Z"),
            episode("new", "2026-10-31T10:00:00Z"),
            episode("newer", "2026-10-31T11:00:00Z"),
            episode("undated", null),
            episode("chapter", "2026-10-31T11:00:00Z", MediaContentKind.AUDIOBOOK),
        )

        assertEquals(2, TabNews.newEpisodes(episodes, looked))
    }

    @Test
    fun `a tab never looked at shows no badge, rather than every episode ever`() {
        assertEquals(0, TabNews.newEpisodes(listOf(episode("new", "2026-10-31T10:00:00Z")), null))
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
