package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.StreamTee
import com.dewijones92.totum.domain.TeeOutcome
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TeeDownloadStrategyTest {

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=abc123")
    private val item = PlayableItem(
        MediaItem(MediaItemId("abc123"), SourceId("s"), "t", publishedAt = null, duration = null, mediaUrl = watch),
        PlayHandle.Video(watch),
    )
    private val target = File("target.media")
    private var fallbackCalls = 0
    private val fallback = DownloadStrategy { _, _, _ ->
        fallbackCalls++
        flowOf(DownloadState.Downloaded("/own/download.media", audioOnly = true))
    }

    private class FakeTee(var teeable: Boolean, var outcome: TeeOutcome) : StreamTee {
        var completed = 0
        override fun canTee(itemId: String) = teeable
        override suspend fun complete(itemId: String, target: File, progress: (Long, Long) -> Unit): TeeOutcome {
            completed++
            progress(10, 100)
            return outcome
        }
    }

    @Test
    fun `audio of an item streaming through the cache is made from those bytes, with no download`() = runTest {
        val tee = FakeTee(true, TeeOutcome.Completed(bytes = 100, teeNetworkBytes = 60, streamNetworkBytes = 100))
        val states = TeeDownloadStrategy(fallback, tee).download(item, target, audioOnly = true).toList()

        assertEquals(0, fallbackCalls)
        assertEquals(1, tee.completed)
        assertEquals(DownloadState.Downloaded(target.absolutePath, audioOnly = true), states.last())
        assertTrue("progress is reported", states.any { it == DownloadState.Downloading(10, 100) })
    }

    @Test
    fun `an item that is not streaming through the cache downloads on its own`() = runTest {
        val tee = FakeTee(false, TeeOutcome.Failed("unused"))
        val states = TeeDownloadStrategy(fallback, tee).download(item, target, audioOnly = true).toList()

        assertEquals(1, fallbackCalls)
        assertEquals(0, tee.completed)
        assertEquals(DownloadState.Downloaded("/own/download.media", audioOnly = true), states.last())
    }

    @Test
    fun `a video download is never made from the audio stream`() = runTest {
        val tee = FakeTee(true, TeeOutcome.Completed(100, 60, 100))
        TeeDownloadStrategy(fallback, tee).download(item, target, audioOnly = false).toList()

        assertEquals(1, fallbackCalls)
        assertEquals(0, tee.completed)
    }

    @Test
    fun `when the stream's bytes cannot be used the download falls back to its own`() = runTest {
        val tee = FakeTee(true, TeeOutcome.Failed("403"))
        val states = TeeDownloadStrategy(fallback, tee).download(item, target, audioOnly = true).toList()

        assertEquals(1, tee.completed)
        assertEquals(1, fallbackCalls)
        assertEquals(DownloadState.Downloaded("/own/download.media", audioOnly = true), states.last())
    }
}
