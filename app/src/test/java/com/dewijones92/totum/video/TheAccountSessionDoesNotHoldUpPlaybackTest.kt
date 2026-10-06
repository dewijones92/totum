package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.SessionResult
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TheAccountSessionDoesNotHoldUpPlaybackTest {

    private val controller = FakePlaybackController()
    private val gate = CompletableDeferred<Unit>()
    private val recorded = FakeYouTubeWatchHistory()
    private val slowAccount = object : YouTubeWatchHistory by recorded {
        override suspend fun beginSession(videoId: String): SessionResult {
            gate.await()
            return recorded.beginSession(videoId)
        }
    }
    private val engine = FakeYtDlpEngine().apply {
        registerMedia(watchUrl(A), FakeYtDlpEngine.sampleMetadata(A))
    }

    @Test
    fun `the video starts while YouTube is still opening the account session`() = runTest {
        val background = CoroutineScope(StandardTestDispatcher(testScheduler))
        val launcher = VideoPlaybackLauncher(
            VideoResolver(engine, SkipSegmentSource { emptyList() }),
            controller,
            slowAccount,
            InMemoryPlayHistoryStore(),
            background = background,
        )

        val play = async { launcher.play(listing(A), watchUrl(A)) }
        advanceUntilIdle()

        assertTrue("play() returned before the session opened", play.isCompleted)
        assertEquals(listOf(A), controller.played)
        assertEquals(emptyList<String>(), recorded.sessions.toList())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(A), recorded.sessions.toList())
        background.cancel()
    }

    private fun watchUrl(id: String) = HttpUrl.of("https://www.youtube.com/watch?v=$id")

    private fun listing(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("youtube"),
        title = "video $id",
        publishedAt = null,
        duration = null,
        mediaUrl = watchUrl(id),
    )

    private companion object {
        const val A = "ytZiDr1NLQc"
    }
}
