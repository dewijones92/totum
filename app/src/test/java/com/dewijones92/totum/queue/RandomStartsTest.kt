package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
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
    fun `a shuffled item starts at its random point once, then resumes as usual`() = runTest {
        val starts = RandomStarts(saved, null, backgroundScope, Random(3))
        starts.assign(listOf(item("a", 10.minutes), item("b", null)), "test")

        val first = starts.resumePositionMs(MediaItemId("a"))
        assertTrue(first != null && first != 42_000L)
        assertEquals(42_000L, starts.resumePositionMs(MediaItemId("a")))
        assertEquals("no duration, no random start", 42_000L, starts.resumePositionMs(MediaItemId("b")))
    }

    @Test
    fun `random starts not yet used survive a restart`() = runTest {
        val file = temp.root.resolve("random-starts.txt")
        val io = UnconfinedTestDispatcher(testScheduler)
        val starts = RandomStarts(saved, file, backgroundScope, Random(5), io)
        starts.assign(listOf(item("a", 10.minutes)), "test")
        val picked = starts.peek(MediaItemId("a"))

        val again = RandomStarts(saved, file, backgroundScope, Random(5), io)
        assertEquals(picked, again.resumePositionMs(MediaItemId("a")))
    }
}
