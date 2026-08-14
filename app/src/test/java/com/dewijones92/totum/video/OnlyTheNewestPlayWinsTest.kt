package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * An extraction takes seconds, and taps arrive during it. Only the newest may start playback.
 *
 * The resolver already de-duplicates the *extraction* — a second caller joins the first rather than
 * running it again ([VideoResolverSharingTest]) — but every joined caller then went on to play.
 * Report 0.1.383: three taps, four seconds apart, while an 11-second extraction ran, and when it
 * landed the same video was handed to the player **three times in 81ms**, with three `beginSession`
 * calls to YouTube for one video.
 *
 * The worse version of the same fault is a tap on a *different* video during a resolve: the older
 * request would then start playing over the one the user had just chosen.
 */
class OnlyTheNewestPlayWinsTest {

    private val controller = FakePlaybackController()
    private val watchHistory = FakeYouTubeWatchHistory()

    /** Slow enough that requests genuinely overlap, as they do on a phone. */
    private class SlowEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            delay(1_000)
            val id = url.value.substringAfter("v=")
            return ExtractionResult.Success(
                MediaMetadata(
                    id = id,
                    title = "video $id",
                    uploader = null,
                    durationSeconds = 10,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat("18", "mp4", 640, 360, true, true, null, "https://x.test/$id", "avc1", "mp4a"),
                    ),
                ),
            )
        }
    }

    private fun launcher() = VideoPlaybackLauncher(
        VideoResolver(SlowEngine(), SkipSegmentSource { emptyList() }),
        controller,
        watchHistory,
        InMemoryPlayHistoryStore(),
    )

    /** The reported case: three taps on one video while it extracts, one play. */
    @Test
    fun `three taps during one extraction start playback once`() = runTest {
        val launcher = launcher()

        val taps = List(3) { async { launcher.play(listing(A), watchUrl(A)) } }
        taps.forEach { it.await() }

        assertEquals("three taps, one play", listOf(A), controller.played)
    }

    /** And YouTube is told about it once, not three times. */
    @Test
    fun `it does not open three tracking sessions for one video`() = runTest {
        val launcher = launcher()

        List(3) { async { launcher.play(listing(A), watchUrl(A)) } }.forEach { it.await() }

        assertEquals(listOf(A), watchHistory.sessions)
    }

    /**
     * The dangerous case. Tapping B while A is resolving must not have A start playing over it —
     * whichever finishes extracting first.
     */
    @Test
    fun `a newer tap on a different video wins`() = runTest {
        val launcher = launcher()

        val first = async { launcher.play(listing(A), watchUrl(A)) }
        val second = async { launcher.play(listing(B), watchUrl(B)) }
        first.await()
        second.await()

        assertEquals("only the video the user last chose should play", listOf(B), controller.played)
    }

    /**
     * A superseded request reports success, not failure. False would make an auto-advance treat
     * the item as unplayable and skip to the NEXT one — so it would fight whatever the user just
     * chose, which is worse than the duplicate play it replaced.
     */
    @Test
    fun `being superseded is not reported as a failure`() = runTest {
        val launcher = launcher()

        val superseded = async { launcher.play(listing(A), watchUrl(A)) }
        async { launcher.play(listing(B), watchUrl(B)) }.await()

        assertEquals(true, superseded.await())
    }

    /** One tap on its own still plays, which is the whole point of the class. */
    @Test
    fun `a single request plays normally`() = runTest {
        val launcher = launcher()

        val played = launcher.play(listing(A), watchUrl(A))

        assertEquals(true, played)
        assertEquals(listOf(A), controller.played)
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
        const val B = "zJoWFydnyAo"
    }
}
