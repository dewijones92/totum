package com.dewijones92.totum.data.ask

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TasteOfTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day

    private fun channel(title: String) =
        MediaSource.VideoChannel(SourceId(title), title, HttpUrl.of("https://www.youtube.com/@x"), null)

    @Test
    fun `starred and liked titles are carried, once each, and capped`() {
        val taste = tasteOf(
            sources = listOf(channel("Kurzgesagt")),
            artists = emptyList(),
            albums = emptyList(),
            listens = listOf(
                StatEvent.Listen(now - day, "p", "i", "A", author = "Kurzgesagt", kind = ListenKind.VIDEO, heardMs = 1),
            ),
            now = now,
            starred = listOf("One", "One", "Two") + (0 until 100).map { "s$it" },
            liked = listOf("Liked", "Liked"),
        )
        assertEquals(60, taste.starred.size)
        assertEquals(listOf("One", "Two"), taste.starred.take(2))
        assertEquals(listOf("Liked"), taste.liked)
        assertEquals(listOf("Kurzgesagt"), taste.played)
    }
}
