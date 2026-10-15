package com.dewijones92.totum.playback

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExploreModeTest {

    private fun video(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("yt"),
            "video $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private val related = mutableMapOf<String, List<String>?>()
    private val kinds = mutableMapOf<String, MediaContentKind>()
    private val membersOnly = mutableSetOf<String>()
    private val peeked = mutableListOf<String>()
    private val playedAt = mutableListOf<Int>()
    private var nowPlaying: PlayableItem? = null
    private var queueCurrent: MediaItemId? = null
    private val queue = listOf("a", "b", "c").map(::MediaItemId)

    private fun explore(on: Boolean = true) = ExploreMode(
        initiallyOn = on,
        related = { item ->
            related[item.id.value]?.map { id ->
                video(
                    id
                ).item.copy(contentKind = kinds[id] ?: MediaContentKind.STANDARD, membersOnly = id in membersOnly)
            }
        },
        peek = { item ->
            peeked += item.item.id.value
            nowPlaying = item
            true
        },
        nowPlaying = { nowPlaying },
        queueCurrent = { queueCurrent },
        queueEntries = { queue },
        playQueueAt = { index ->
            playedAt += index
            true
        },
    )

    @Test
    fun `on, an ended queue item hands over to a related video and the queue's place is kept`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = listOf("x", "y")

        assertTrue(explore.afterEnded(MediaItemId("a")))

        assertEquals(listOf("x"), peeked)
        assertEquals(MediaItemId("a"), explore.resumeAfter)
    }

    @Test
    fun `it never goes back to something it has already played in this run`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = listOf("x")
        related["x"] = listOf("a", "x", "z")
        explore.afterEnded(MediaItemId("a"))
        queueCurrent = null

        explore.afterEnded(MediaItemId("x"))

        assertEquals(listOf("x", "z"), peeked)
        assertEquals("still the queue's place, not an explored video", MediaItemId("a"), explore.resumeAfter)
    }

    @Test
    fun `switched off, the queue carries on from after the item it left`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = listOf("x")
        explore.afterEnded(MediaItemId("a"))
        explore.set(false, "test")

        assertFalse(explore.afterEnded(MediaItemId("x")))
        assertEquals(true, explore.resumeQueue())

        assertEquals(listOf(1), playedAt)
        assertNull(explore.resumeAfter)
    }

    @Test
    fun `with nothing explored there is nothing to resume`() = runTest {
        assertNull(explore(on = false).resumeQueue())
    }

    @Test
    fun `no related videos lets the queue carry on`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = null

        assertFalse(explore.afterEnded(MediaItemId("a")))
        assertEquals(emptyList<String>(), peeked)
    }

    @Test
    fun `a tap into the queue while exploring moves the place to resume from`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = listOf("x")
        related["c"] = listOf("y")
        explore.afterEnded(MediaItemId("a"))

        nowPlaying = video("c")
        queueCurrent = MediaItemId("c")
        explore.afterEnded(MediaItemId("c"))

        assertEquals(MediaItemId("c"), explore.resumeAfter)
    }

    @Test
    fun `Next while exploring plays another related video, and after switching off it resumes the queue`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = listOf("x")
        related["x"] = listOf("y")
        explore.afterEnded(MediaItemId("a"))
        queueCurrent = null

        assertEquals(true, explore.next())
        assertEquals(listOf("x", "y"), peeked)

        explore.set(false, "test")
        assertEquals(true, explore.next())
        assertEquals("after the queue item it left", listOf(1), playedAt)
    }

    @Test
    fun `Next with nothing to say leaves the queue to decide`() = runTest {
        assertNull(explore(on = false).next())
    }

    @Test
    fun `the queue's place survives the app being restarted`() = runTest {
        var saved: MediaItemId? = null
        val first = ExploreMode(
            initiallyOn = true,
            related = { item -> related[item.id.value]?.map { video(it).item } },
            peek = { true },
            nowPlaying = { nowPlaying },
            queueCurrent = { queueCurrent },
            queueEntries = { queue },
            playQueueAt = { true },
            saveResume = { saved = it },
        )
        nowPlaying = video("b")
        queueCurrent = MediaItemId("b")
        related["b"] = listOf("x")
        first.afterEnded(MediaItemId("b"))

        val restarted = ExploreMode(
            initiallyOn = false,
            related = { null },
            peek = { true },
            nowPlaying = { null },
            queueCurrent = { null },
            queueEntries = { queue },
            playQueueAt = { index ->
                playedAt += index
                true
            },
            resumeAtStart = saved,
        )
        restarted.resumeQueue()

        assertEquals(listOf(2), playedAt)
    }

    @Test
    fun `it never picks a live stream, a Short or a members-only video`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        queueCurrent = MediaItemId("a")
        related["a"] = listOf("live", "short", "members", "ok")
        kinds["live"] = MediaContentKind.LIVE
        kinds["short"] = MediaContentKind.SHORT
        membersOnly += "members"

        explore.afterEnded(MediaItemId("a"))

        assertEquals(listOf("ok"), peeked)
    }
}
