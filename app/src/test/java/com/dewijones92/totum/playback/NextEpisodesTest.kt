package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class NextEpisodesTest {

    private fun episode(id: String, day: Long?, show: String = "show") = MediaItem(
        MediaItemId(id),
        SourceId(show),
        "episode $id",
        publishedAt = day?.let { Instant.ofEpochSecond(it * 86_400) },
        duration = null,
        mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
    )

    @Test
    fun `the next episode of the same show comes first, then the older ones newest first`() {
        val all =
            listOf(episode("e1", 1), episode("e2", 2), episode("e3", 3), episode("e4", 4), episode("o", 5, "other"))

        val next = nextEpisodesOf(all[1], all, finished = { false })

        assertEquals(listOf("e3", "e4", "e1"), next.map { it.id.value })
    }

    @Test
    fun `finished episodes are skipped`() {
        val all = listOf(episode("e1", 1), episode("e2", 2), episode("e3", 3), episode("e4", 4))

        val next = nextEpisodesOf(all[0], all, finished = { it.value == "e2" })

        assertEquals(listOf("e3", "e4"), next.map { it.id.value })
    }

    @Test
    fun `with no date on the ended one, newest first`() {
        val all = listOf(episode("e1", 1), episode("e2", 2), episode("x", null))

        assertEquals(listOf("e2", "e1"), nextEpisodesOf(all[2], all, finished = { false }).map { it.id.value })
    }
}
