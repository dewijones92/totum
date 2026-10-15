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
    private var nowPlaying: PlayableItem? = null
    private var turnedOn = 0
    private var whileLookingUp: () -> Unit = {}

    private fun explore(on: Boolean = true) = ExploreMode(
        initiallyOn = on,
        related = { item ->
            whileLookingUp()
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
        onTurnedOn = { turnedOn++ },
    )

    @Test
    fun `on, an ended item hands over to a related video`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        related["a"] = listOf("x", "y")

        assertTrue(explore.afterEnded(MediaItemId("a")))
        assertEquals(listOf("x"), peeked)
    }

    @Test
    fun `it never goes back to something it has already played in this run`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        related["a"] = listOf("x")
        related["x"] = listOf("a", "x", "z")
        explore.afterEnded(MediaItemId("a"))

        explore.afterEnded(MediaItemId("x"))

        assertEquals(listOf("x", "z"), peeked)
    }

    @Test
    fun `off, it leaves the end to the queue`() = runTest {
        val explore = explore(on = false)
        nowPlaying = video("a")
        related["a"] = listOf("x")

        assertFalse(explore.afterEnded(MediaItemId("a")))
        assertNull(explore.next())
    }

    @Test
    fun `no related videos lets the queue carry on`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        related["a"] = null

        assertFalse(explore.afterEnded(MediaItemId("a")))
        assertEquals(emptyList<String>(), peeked)
    }

    @Test
    fun `it never picks a live stream, a Short or a members-only video`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        related["a"] = listOf("live", "short", "members", "ok")
        kinds["live"] = MediaContentKind.LIVE
        kinds["short"] = MediaContentKind.SHORT
        membersOnly += "members"

        explore.afterEnded(MediaItemId("a"))

        assertEquals(listOf("ok"), peeked)
    }

    @Test
    fun `Next while exploring plays another related video`() = runTest {
        val explore = explore()
        nowPlaying = video("x")
        related["x"] = listOf("y")

        assertEquals(true, explore.next())
        assertEquals(listOf("y"), peeked)
    }

    @Test
    fun `a related list that arrives after you chose something else does not interrupt it`() = runTest {
        val explore = explore()
        nowPlaying = video("a")
        related["a"] = listOf("x")
        whileLookingUp = { nowPlaying = video("your-choice") }

        assertFalse(explore.afterEnded(MediaItemId("a")))
        assertEquals(emptyList<String>(), peeked)
    }

    @Test
    fun `turning it on drops whatever gapless had already put in line`() {
        val explore = explore(on = false)

        explore.set(true, "test")
        explore.set(true, "test again")

        assertEquals(1, turnedOn)
    }
}
