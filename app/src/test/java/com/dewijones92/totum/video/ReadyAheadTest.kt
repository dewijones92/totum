package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadyAheadTest {

    private val asked = mutableListOf<String>()
    private var metered = false

    private fun video(id: String) = HttpUrl.of("https://www.youtube.com/watch?v=$id").let { url ->
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("s"), id, publishedAt = null, duration = null, mediaUrl = url),
            PlayHandle.Video(url)
        )
    }

    private fun TestScope.readyAhead(slowMs: Long = 0) = ReadyAhead(
        metered = { metered },
        prefetch = { url, _ ->
            asked += url.value.substringAfter("v=")
            delay(slowMs)
            null
        },
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)).also { worker = it },
    ).also { it.start() }

    private var worker: CoroutineScope? = null

    @After
    fun stopTheWorker() {
        worker?.cancel()
    }

    @Test
    fun `a video is looked up in the background`() = runTest {
        readyAhead().ready(video("a"), "menu opened")
        advanceUntilIdle()
        assertEquals(listOf("a"), asked)
    }

    @Test
    fun `lookups run one at a time, newest wish first`() = runTest {
        val ahead = readyAhead(slowMs = 1_000)
        ahead.ready(video("first"), "menu opened")
        delay(10)
        ahead.ready(video("older"), "next in queue")
        ahead.ready(video("newest"), "menu opened")
        advanceUntilIdle()
        assertEquals(listOf("first", "newest", "older"), asked)
    }

    @Test
    fun `asking again for a waiting video does not look it up twice`() = runTest {
        val ahead = readyAhead(slowMs = 1_000)
        ahead.ready(video("busy"), "menu opened")
        delay(10)
        ahead.ready(video("b"), "menu opened")
        ahead.ready(video("b"), "next in queue")
        advanceUntilIdle()
        assertEquals(listOf("busy", "b"), asked)
    }

    @Test
    fun `only the few newest wishes wait and older ones are dropped`() = runTest {
        val ahead = readyAhead(slowMs = 1_000)
        ahead.ready(video("busy"), "menu opened")
        delay(10)
        listOf("1", "2", "3", "4", "5", "6").forEach { ahead.ready(video(it), "menu opened") }
        advanceUntilIdle()
        assertEquals(listOf("busy", "6", "5", "4", "3"), asked)
    }

    @Test
    fun `nothing is spent on a metered network`() = runTest {
        metered = true
        readyAhead().ready(video("a"), "menu opened")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a podcast has nothing to look up`() = runTest {
        val enclosure = HttpUrl.of("https://example.test/ep.mp3")
        val episode = PlayableItem(
            MediaItem(
                MediaItemId("ep"),
                SourceId("f"),
                "Ep",
                publishedAt = null,
                duration = null,
                mediaUrl = enclosure
            ),
            PlayHandle.Podcast(),
        )
        readyAhead().ready(episode, "menu opened")
        advanceUntilIdle()
        assertEquals(emptyList<String>(), asked)
    }

    @Test
    fun `a video played from its downloaded file is still looked up by its watch URL`() = runTest {
        readyAhead().ready(video("lib").copy(handle = PlayHandle.Podcast("/downloads/lib.m4a")), "audio copy playing")
        advanceUntilIdle()
        assertEquals(listOf("lib"), asked)
    }

    @Test
    fun `each new item playing readies the one after it, unless that is on the disk`() = runTest {
        val playing = MutableStateFlow<PlayableItem?>(null)
        var next: PlayableItem? = video("n1")
        val onDisk = setOf("n2")
        readyAhead().followQueue(playing, nextUp = { next }, hasLocalCopy = { it.value in onDisk })
        playing.value = video("now1")
        advanceUntilIdle()
        next = video("n2")
        playing.value = video("now2")
        advanceUntilIdle()
        assertEquals(listOf("n1"), asked)
    }
}
