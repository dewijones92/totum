package com.dewijones92.totum.ui.player

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.Listening
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.RepeatMode
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.NextLine
import com.dewijones92.totum.playback.NextLineInputs
import com.dewijones92.totum.playback.nextLineOf
import com.dewijones92.totum.queue.SkipWhy
import com.dewijones92.totum.queue.nextAfterThePlace
import com.dewijones92.totum.queue.passOverReason
import com.dewijones92.totum.queue.upNextSkips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextViewTest {

    private fun entry(id: String, kind: MediaContentKind = MediaContentKind.STANDARD) = QueueEntry(
        PlayableItem(
            MediaItem(
                id = MediaItemId(id),
                sourceId = SourceId("feed"),
                title = "Title $id",
                publishedAt = null,
                duration = null,
                mediaUrl = HttpUrl.of("https://feeds.example.com/$id.mp3"),
                contentKind = kind,
            ),
            PlayHandle.Podcast(),
        ),
    )

    private val finished = setOf("t2", "t3", "m2")
    private val entries = listOf(
        entry("t1"),
        entry("t2"),
        entry("t3"),
        entry("t4"),
        entry("m1", MediaContentKind.MUSIC),
        entry("m2", MediaContentKind.MUSIC),
        entry("t5"),
    )

    private fun passOver(repeat: RepeatMode = RepeatMode.OFF): (PlayableItem) -> String? = { item ->
        passOverReason(
            item,
            repeat,
            music = item.item.contentKind == MediaContentKind.MUSIC,
            states = finished.associate { MediaItemId(it) to PlayState.Played },
        )
    }

    private fun skipsWhilePlaying(id: String, repeat: RepeatMode = RepeatMode.OFF): Map<MediaItemId, SkipWhy> {
        val at = entries.indexOfFirst { it.item.item.id.value == id }
        return upNextSkips(entries.drop(at + 1), entries[at].item.item, passOver(repeat))
    }

    @Test
    fun `finished talk and the other kind are the entries the queue will pass over`() {
        val skips = skipsWhilePlaying("t1")

        assertEquals(setOf("t2", "t3", "m1", "m2"), skips.keys.map { it.value }.toSet())
        assertEquals(SkipWhy.PassedOver("finished"), skips[MediaItemId("t2")])
        assertEquals(SkipWhy.OtherKind(Listening.MUSIC), skips[MediaItemId("m1")])
    }

    @Test
    fun `a finished song is not passed over, only skipped for being music while talk plays`() {
        val skips = skipsWhilePlaying("t1")

        assertTrue(skips[MediaItemId("m2")] is SkipWhy.OtherKind)
    }

    @Test
    fun `with a repeat on nothing finished is passed over, as in the advance`() {
        val skips = skipsWhilePlaying("t1", RepeatMode.QUEUE)

        assertEquals(setOf("m1", "m2"), skips.keys.map { it.value }.toSet())
    }

    @Test
    fun `the first entry not marked skipped is the one the advance and the notification line start`() {
        val at = entries.indexOfFirst { it.item.item.id.value == "t1" }
        val skips = skipsWhilePlaying("t1")
        val snapshot = QueueSnapshot(entries, at)
        val upNext = entries.drop(at + 1)

        val firstShown = upNext.first { it.item.item.id !in skips }.item.item.id
        val advance = nextAfterThePlace(snapshot, MediaItemId("t1"), Listening.TALK, passOver())
        val line = nextLineOf(
            NextLineInputs(snapshot, entries[at].item.item, RepeatMode.OFF, true, false, null),
            passOver(),
        )

        assertEquals(firstShown, advance?.item?.id)
        assertEquals(NextLine.Queued("Title ${firstShown.value}"), line)
    }
}
