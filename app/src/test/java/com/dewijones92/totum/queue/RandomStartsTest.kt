package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.NoOpPlaybackProgressStore
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

class RandomStartsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun item(id: String, duration: Duration?) =
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("s"),
            title = id,
            publishedAt = null,
            duration = duration,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        )

    private val saved = object : PlaybackProgressStore by NoOpPlaybackProgressStore {
        override suspend fun resumePositionMs(itemId: MediaItemId): Long = 42_000
    }

    @Test
    fun `a random start lands anywhere from the start to just before the end`() {
        val random = Random(1)
        val starts = List(2_000) { randomStartMs(10.minutes.inWholeMilliseconds, random)!! }
        assertTrue(starts.all { it in 0 until 10.minutes.inWholeMilliseconds - 10_000 })
        assertTrue("some start in the first minute", starts.any { it < 60_000 })
        assertTrue("some start in the last two minutes", starts.any { it > 8.minutes.inWholeMilliseconds })
        assertNull(randomStartMs(0, random))
    }

    @Test
    fun `reading a random start does not use it up, so arming the next item cannot lose it`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes), item("b", null)), "test", atRandom = true)

        val armed = starts.resumePositionMs(MediaItemId("a"))
        val played = starts.resumePositionMs(MediaItemId("a"))

        assertTrue(armed != null && armed != 42_000L)
        assertEquals(armed, played)
        assertEquals(
            "no duration yet: from the top until the player knows it",
            0L,
            starts.resumePositionMs(MediaItemId("b"))
        )
    }

    @Test
    fun `an item with no known length gets its random point once the player knows the length`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("torrent", null)), "test", atRandom = true)

        val picked = starts.startOnceLengthKnown(MediaItemId("torrent"), 10.minutes.inWholeMilliseconds)

        assertTrue("picked $picked", picked != null && picked in 0 until 10.minutes.inWholeMilliseconds - 10_000)
        assertEquals("a re-fetch keeps the point", picked, starts.resumePositionMs(MediaItemId("torrent")))
        assertNull("asked again, nothing more to do", starts.startOnceLengthKnown(MediaItemId("torrent"), 600_000))
    }

    @Test
    fun `learning the length moves nothing that was not shuffled to a random point`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("known", 10.minutes)), "test", atRandom = true)
        starts.assign(listOf(item("top", null)), "test", atRandom = false)

        assertNull(starts.startOnceLengthKnown(MediaItemId("known"), 600_000))
        assertNull(starts.startOnceLengthKnown(MediaItemId("top"), 600_000))
        assertNull(starts.startOnceLengthKnown(MediaItemId("never-shuffled"), 600_000))
    }

    @Test
    fun `once the item has played and saved its place, it resumes as usual`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes)), "test", atRandom = true)

        starts.save(MediaItemId("a"), 120_000, 600_000, Chosen.NO)

        assertEquals(42_000L, starts.resumePositionMs(MediaItemId("a")))
    }

    @Test
    fun `a random start is forgotten when its item leaves the queue`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes), item("b", 10.minutes)), "test", atRandom = true)

        starts.keepOnly(setOf(MediaItemId("b")))

        assertEquals(42_000L, starts.resumePositionMs(MediaItemId("a")))
        assertTrue(starts.peek(MediaItemId("b")) != null)
    }

    @Test
    fun `random starts not yet used survive a restart`() = runTest {
        val file = temp.root.resolve("random-starts.txt")
        val io = UnconfinedTestDispatcher(testScheduler)
        val starts = RandomStarts(saved, file, backgroundScope, Random(5), io)
        starts.assign(listOf(item("a", 10.minutes)), "test", atRandom = true)
        val picked = starts.peek(MediaItemId("a"))

        val again = RandomStarts(saved, file, backgroundScope, Random(5), io)
        assertEquals(picked, again.resumePositionMs(MediaItemId("a")))
    }

    @Test
    fun `a shuffle without random starts plays each item from the top once, then resumes as usual`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes), item("b", null)), "test", atRandom = false)

        assertEquals(0L, starts.resumePositionMs(MediaItemId("a")))
        assertEquals("no duration still starts from the top", 0L, starts.resumePositionMs(MediaItemId("b")))

        starts.save(MediaItemId("a"), 120_000, 600_000, Chosen.NO)
        assertEquals(42_000L, starts.resumePositionMs(MediaItemId("a")))
    }

    @Test
    fun `a later shuffle from the top replaces an earlier random start`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes)), "first", atRandom = true)
        starts.assign(listOf(item("a", 10.minutes)), "second", atRandom = false)

        assertEquals(0L, starts.resumePositionMs(MediaItemId("a")))
    }

    @Test
    fun `a shuffle nobody was asked about, like a pinned replay, leaves every position alone`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes)), "pinned replay", atRandom = null)

        assertEquals(42_000L, starts.resumePositionMs(MediaItemId("a")))
    }
}
