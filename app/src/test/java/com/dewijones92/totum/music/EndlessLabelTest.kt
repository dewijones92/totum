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
import com.dewijones92.totum.domain.withStreamFrom
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
    fun `a radio song is played as endless radio, and its own artist is left alone`() {
        val song = playable("s1", "Paco de Lucía")
        val snapshot = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("radio:RDAMVMx", "Radio · Entre dos aguas"))))

        val played = song.labelledForPlay(snapshot, liveRadioGroup = "radio:RDAMVMx").item
        assertEquals(ENDLESS_MARK, played.playedAs)
        assertEquals("Paco de Lucía", played.author)
    }

    @Test
    fun `a radio block that is not the live station is not called endless`() {
        val song = playable("s1", "Michael Bublé")
        val snapshot = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("radio:RDAMVMold", "Radio · Christmas"))))

        assertEquals(RADIO_MARK, song.labelledForPlay(snapshot, liveRadioGroup = "radio:discover:UC1").item.playedAs)
        assertEquals(RADIO_MARK, song.labelledForPlay(snapshot, liveRadioGroup = null).item.playedAs)
    }

    @Test
    fun `a discover block keeps its own mark whether or not it is live`() {
        val song = playable("s1", "Paco de Lucía")
        val snapshot = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("radio:discover:UC1", "Discover"))))

        assertEquals(DISCOVER_MARK, song.labelledForPlay(snapshot, liveRadioGroup = null).item.playedAs)
    }

    @Test
    fun `the label survives the stream route, which replaces the author with the resolved one`() {
        val song = playable("s1", "Paco de Lucía - Topic")
        val snapshot = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("radio:RDAMVMx", "Radio"))))
        val resolved = song.item.copy(author = "Paco de Lucía")

        val streamed = song.labelledForPlay(snapshot, liveRadioGroup = "radio:RDAMVMx").item.withStreamFrom(resolved)

        assertEquals(ENDLESS_MARK, streamed.playedAs)
        assertEquals("Paco de Lucía", streamed.author)
    }

    @Test
    fun `a song from an album, or not queued, plays as itself`() {
        val song = playable("s1", "Paco de Lucía")
        val album = QueueSnapshot(listOf(QueueEntry(song, QueueGroup("album:x", "Almoraima"))))

        assertEquals(null, song.labelledForPlay(album, liveRadioGroup = null).item.playedAs)
        assertEquals(null, song.labelledForPlay(QueueSnapshot(), liveRadioGroup = null).item.playedAs)
    }

    @Test
    fun `the stats log strips the mark from the artist line it reads back`() {
        assertEquals("Paco de Lucía", "$ENDLESS_MARK · Paco de Lucía".withoutEndlessMark())
        assertEquals("Paco de Lucía", "$RADIO_MARK · Paco de Lucía".withoutEndlessMark())
        assertEquals("Paco de Lucía", "Paco de Lucía".withoutEndlessMark())
    }
}
