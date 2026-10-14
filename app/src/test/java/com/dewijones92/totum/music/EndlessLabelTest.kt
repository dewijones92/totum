package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Test

class EndlessLabelTest {

    private fun playable(id: String, author: String?) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = null,
            author = author,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    @Test
    fun `a song from a radio is played as endless radio, wherever its artist is shown`() {
        val song = playable("s1", "Paco de Lucía")
        val snapshot = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("radio:RDAMVMx", "Radio · Entre dos aguas"))))

        assertEquals("♾️ Endless radio · Paco de Lucía", song.labelledForPlay(snapshot).item.author)
    }

    @Test
    fun `a song from an album, or not queued, plays as itself`() {
        val song = playable("s1", "Paco de Lucía")
        val album = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("album:x", "Almoraima"))))

        assertEquals("Paco de Lucía", song.labelledForPlay(album).item.author)
        assertEquals("Paco de Lucía", song.labelledForPlay(QueueSnapshot()).item.author)
    }

    @Test
    fun `the mark is added once, and stands alone when there is no artist`() {
        assertEquals("♾️ Endless radio · A", endlessArtist(endlessArtist("A")))
        assertEquals("♾️ Endless radio", endlessArtist(null))
    }
}
