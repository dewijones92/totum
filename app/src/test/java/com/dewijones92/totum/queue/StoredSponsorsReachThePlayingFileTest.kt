package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.download.DownloadRequest
import com.dewijones92.totum.data.download.DownloadStore
import com.dewijones92.totum.data.download.SkipSegmentsOnDisk
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SegmentLookup
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class StoredSponsorsReachThePlayingFileTest {
    @Test
    fun anotherRefreshFinishingDuringRouteSelectionStillUpdatesThePlayingFile() = runTest {
        val watch = HttpUrl.of("https://www.youtube.com/watch?v=abc123def45")
        val item = PlayableItem(
            MediaItem(
                MediaItemId("abc123def45"),
                SourceId("youtube"),
                "A talk",
                publishedAt = null,
                duration = null,
                mediaUrl = watch
            ),
            PlayHandle.Video(watch),
        )
        val segments = listOf(SkipSegment(10.seconds, 20.seconds))
        val store = SponsorStore(DownloadedMedia(item, "/downloads/talk.m4a", audioOnly = true))
        val disk = SkipSegmentsOnDisk({ SegmentLookup.Answered(segments) }, store)
        val controller = FakePlaybackController()
        val work = CoroutineScope(coroutineContext + SupervisorJob())
        val queue = PlaybackQueue(
            controller,
            VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            work,
            audioPreferred = { true },
            localCopy = { id ->
                val beforeRefresh = disk.toSkip(id)
                disk.refresh(id)
                LocalCopy("/downloads/talk.m4a", audioOnly = true, skipSegments = beforeRefresh)
            },
            refreshSkipsOnDisk = disk::refresh,
        )

        queue.peek(item)
        advanceUntilIdle()
        work.cancel()

        assertEquals(item.item.id, controller.state.value?.itemId)
        assertEquals(segments, controller.state.value?.skipSegments)
        assertEquals(segments, disk.toSkip(item.item.id))
    }

    private class SponsorStore(copy: DownloadedMedia) : DownloadStore {
        private val copies = MutableStateFlow(listOf(copy))
        override fun observeDownloaded(): Flow<List<DownloadedMedia>> = copies
        override suspend fun rememberSkipSegments(id: MediaItemId, segments: List<SkipSegment>) {
            copies.value = copies.value.map { if (it.item.id == id) it.copy(skipSegments = segments) else it }
        }
        override suspend fun learnFacts(resolved: MediaItem): Unit = error("unused")
        override fun observeAll(): Flow<Map<MediaItemId, DownloadState>> = error("unused")
        override suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean): Unit = error("unused")
        override suspend fun get(id: MediaItemId): DownloadState = error("unused")
        override suspend fun request(id: MediaItemId): DownloadRequest? = error("unused")
        override suspend fun remove(id: MediaItemId): Unit = error("unused")
        override fun observeRecords(): Flow<List<DownloadRecord>> = error("unused")
    }
}
