package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinIdsTest {

    private fun item(id: String, title: String, author: String?) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = title,
        publishedAt = null,
        duration = null,
        author = author,
    )

    @Test
    fun `an episode carries its season and episode, a film carries none`() {
        val episode = item("jellyfin:abcd1234", "S02E05 · The Creek", "Bluey")
        val film = item("jellyfin:ef567890", "Heat", null)

        assertEquals(EpisodeCode(2, 5), episode.jellyfinEpisode)
        assertNull(film.jellyfinEpisode)
        assertTrue(film.isJellyfinFilm)
        assertFalse(episode.isJellyfinFilm)
    }

    @Test
    fun `an item from elsewhere is neither a Jellyfin episode nor a Jellyfin film`() {
        val other = item("torrent:abc", "Show.S01E02.720p", "Show")

        assertNull(other.jellyfinEpisode)
        assertFalse(other.isJellyfinFilm)
    }
}
