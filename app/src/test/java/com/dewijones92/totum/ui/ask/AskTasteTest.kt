package com.dewijones92.totum.ui.ask

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AskTasteTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day
    private fun channel(title: String) =
        MediaSource.VideoChannel(SourceId(title), title, HttpUrl.of("https://www.youtube.com/@x"), null)
    private fun show(title: String) =
        MediaSource.PodcastFeed(SourceId(title), title, HttpUrl.of("https://example.com/feed"))
    private fun listen(title: String, author: String?, kind: ListenKind, heardMs: Long, daysAgo: Long = 1) =
        StatEvent.Listen(now - daysAgo * day, "p", "i", title, author = author, kind = kind, heardMs = heardMs)

    @Test
    fun `taste splits channels from shows and ranks what was heard most lately`() {
        val taste = tasteOf(
            listOf(channel("Bald and Bankrupt"), show("The Rest Is Politics")),
            artists = listOf("Jackson 5"),
            albums = listOf("ABC"),
            listens = listOf(
                listen("ABC", "Jackson 5", ListenKind.MUSIC, 1_000),
                listen("September", "Earth, Wind & Fire", ListenKind.MUSIC, 9_000),
                listen("Ep 1", "Peep Show", ListenKind.VIDEO, 5_000),
                listen("Ep 2", "Peep Show", ListenKind.VIDEO, 5_000),
                listen("Old", "Forgotten", ListenKind.PODCAST, 99_000, daysAgo = 200),
                listen("Week", "Rest Is Politics", ListenKind.PODCAST, 6_000),
            ),
            now = now,
        )
        assertEquals(listOf("Bald and Bankrupt"), taste.channels)
        assertEquals(listOf("The Rest Is Politics"), taste.shows)
        assertEquals(listOf("September — Earth, Wind & Fire", "ABC — Jackson 5"), taste.songs)
        assertEquals(listOf("Peep Show", "Rest Is Politics"), taste.played)
    }

    @Test
    fun `a channel is found by its name whatever the spacing and case, the exact one first`() {
        val sources = listOf(channel("Bald and Bankrupt Extra"), channel("Bald and Bankrupt"), show("Ben"))
        assertEquals("Bald and Bankrupt", sourceNamed("baldandbankrupt", sources)?.title)
        assertEquals("Bald and Bankrupt Extra", sourceNamed("Bald & Bankrupt Extra", sources)?.title)
        assertEquals("Bald and Bankrupt", sourceNamed("Bald and Bankrupt", sources)?.title)
        assertNull(sourceNamed("Someone else", sources))
    }
}
