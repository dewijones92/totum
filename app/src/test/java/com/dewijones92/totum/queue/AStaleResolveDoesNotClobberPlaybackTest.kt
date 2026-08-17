package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * A resolve that lands after playback has moved on must not apply itself — whatever route the
 * newer play took.
 *
 * Report 0.1.390, and Dewi's note on it, is the whole argument. Timeline from his phone:
 *
 * ```
 * 20:56:17.066 queue play-at-4 "Discoveries That Confirmed Ancient Folklore"   (watching)
 * 20:56:17.075 route -> streaming the video                                     resolve begins
 * 20:56:19.351 settings playbackMode -> AUDIO
 * 20:56:19.359 route -> the downloaded audio at /data/…/3138547848.media
 * 20:56:19.868 ready after 464ms — playing                                      ✅ from disk
 * 20:56:29.286 engine extract … in 12210ms                                      the OLD resolve lands
 * 20:56:29.548 listening — audio track preferred
 * 20:56:29.548 play … from https://…googlevideo.com/videoplayback?…             ❌ the file is dropped
 * 20:56:32.857 ERROR_CODE_IO_BAD_HTTP_STATUS — stream failed
 * 20:56:32.857 gave up buffering after 3308ms — it never recovered
 * ```
 *
 * A twelve-second extraction, twelve seconds stale by the time it arrived, replaced a perfectly
 * good local file with a network stream that answered 403 — and then a re-resolve spiral on top.
 * `playback.bufferingMs` for that session was 56s and `abandonedBufferingMs` 41s, which is what
 * "more buffering" meant.
 *
 * [com.dewijones92.totum.video.OnlyTheNewestPlayWinsTest] already pinned the guard for a newer
 * *launcher* play, and it held. What it could not see is that the guard counted only the
 * launcher's own plays: the queue reaches a file through [PlaybackController.play] directly, so
 * a route to disk left the older streaming resolve believing it was still the newest thing
 * anybody wanted. The guard has to be claimed by every route, which is why this test lives at
 * the queue rather than at the launcher.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AStaleResolveDoesNotClobberPlaybackTest {

    private val dispatcher = StandardTestDispatcher()
    private val controller = FakePlaybackController()

    /** What the download store answers, mutable so a test can make a copy appear mid-play. */
    private val onDisk = mutableMapOf<MediaItemId, LocalCopy>()

    private var audioPreferred = false

    /** As slow as the real thing on his phone: the extraction under test took 12 seconds. */
    private class SlowEngine : YtDlpEngine by FakeYtDlpEngine() {
        override suspend fun extract(url: HttpUrl): ExtractionResult {
            delay(EXTRACT_MS)
            val id = url.value.substringAfter("v=")
            return ExtractionResult.Success(
                MediaMetadata(
                    id = id,
                    title = "video $id",
                    uploader = null,
                    durationSeconds = 600,
                    thumbnailUrl = null,
                    formats = listOf(
                        MediaFormat(
                            "18", "mp4", 640, 360, true, true, null, STREAM_URL, "avc1", "mp4a",
                        ),
                    ),
                ),
            )
        }
    }

    private fun queue() = PlaybackQueue(
        controller = controller,
        launcher = VideoPlaybackLauncher(
            VideoResolver(SlowEngine(), SkipSegmentSource { emptyList() }),
            controller,
            FakeYouTubeWatchHistory(),
            InMemoryPlayHistoryStore(),
            audioPreferred = { audioPreferred },
        ),
        scope = CoroutineScope(dispatcher),
        offline = { false },
        audioPreferred = { audioPreferred },
        localCopy = { id -> onDisk[id] },
    )

    private val item = PlayableItem(
        item = MediaItem(
            id = MediaItemId(VIDEO_ID),
            sourceId = SourceId("youtube"),
            title = "Discoveries That Confirmed Ancient Folklore",
            publishedAt = null,
            duration = null,
            mediaUrl = null,
        ),
        handle = PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$VIDEO_ID")),
    )

    /**
     * His exact sequence: watching, so it streams; then Listen mode with the audio downloaded,
     * so the same item routes to the file; then the first resolve lands.
     */
    @Test
    fun `a file that started playing is not replaced by an older resolve`() = runTest(dispatcher) {
        // Downloaded audio-only, which is what the queue's auto-download fetches.
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(LOCAL_PATH, audioOnly = true)
        val queue = queue()

        // Watching: an audio-only copy does not stand in, so this streams — and resolves slowly.
        launch { queue.peek(item) }
        advanceTimeBy(SWITCH_AFTER_MS)

        // He switches to Listen mode and plays it again; now the copy on disk is the right answer.
        audioPreferred = true
        launch { queue.peek(item) }
        advanceUntilIdle()

        assertNotNull("the file should have been played", controller.lastLocalPath)
        assertEquals(
            "the stale resolve must not have taken playback back to the network",
            LOCAL_PATH,
            controller.lastLocalPath,
        )
    }

    /** And it is not handed to the player at all — a superseded resolve does nothing, quietly. */
    @Test
    fun `the stale stream is never handed to the player`() = runTest(dispatcher) {
        onDisk[MediaItemId(VIDEO_ID)] = LocalCopy(LOCAL_PATH, audioOnly = true)
        val queue = queue()

        launch { queue.peek(item) }
        advanceTimeBy(SWITCH_AFTER_MS)
        audioPreferred = true
        launch { queue.peek(item) }
        advanceUntilIdle()

        assertEquals(
            "one play from disk, and no play from the network",
            1,
            controller.played.size,
        )
    }

    /** The guard must not swallow an ordinary play: on its own, the stream still plays. */
    @Test
    fun `with nothing newer, the resolve still plays`() = runTest(dispatcher) {
        val queue = queue()

        launch { queue.peek(item) }
        advanceUntilIdle()

        assertEquals(listOf(VIDEO_ID), controller.played)
        assertEquals(STREAM_URL, controller.lastItem?.mediaUrl?.value)
    }

    private companion object {
        const val VIDEO_ID = "ng2Tsa5KE_A"
        const val LOCAL_PATH = "/data/user/0/com.dewijones92.totum/files/downloads/3138547848.media"
        const val STREAM_URL = "https://rr1---sn-8vq54vox03-cgne.googlevideo.test/videoplayback?itag=18"

        /** The gap between his two plays was 2.3s; the extraction it raced was 12.2s. */
        const val EXTRACT_MS = 12_000L
        const val SWITCH_AFTER_MS = 2_300L
    }
}
