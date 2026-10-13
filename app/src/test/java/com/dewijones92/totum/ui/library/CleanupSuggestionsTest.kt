package com.dewijones92.totum.ui.library

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.CleanupGroup
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CleanupSuggestionsTest {

    private val dispatcher = StandardTestDispatcher()
    private val downloads = FakeDownloadManager()
    private val states = MutableStateFlow<Map<MediaItemId, PlayState>>(emptyMap())
    private val now = Instant.parse("2026-10-13T12:00:00Z")
    private lateinit var queue: PlaybackQueue

    private fun episode(id: String) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("src"),
        title = id,
        publishedAt = null,
        duration = null,
        mediaUrl = HttpUrl.of("https://cdn.example.com/$id.mp3"),
    ).asPlayable()

    private fun onDisk(id: String, downloadedDaysAgo: Long, openedDaysAgo: Long? = null) {
        downloads.place(
            DownloadedMedia(
                episode(id),
                "/fake/$id.media",
                audioOnly = true,
                downloadedAt = now.minus(downloadedDaysAgo, ChronoUnit.DAYS),
                openedAt = openedDaysAgo?.let { now.minus(it, ChronoUnit.DAYS) },
            ),
        )
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val controller = FakePlaybackController()
        queue = PlaybackQueue(
            controller,
            VideoPlaybackLauncher(
                VideoResolver(FakeYtDlpEngine(), SkipSegmentSource { emptyList() }),
                controller,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model() = LibraryViewModel(
        queue,
        downloads,
        fileSize = { if (it.contains("big")) BIG else SMALL },
        playStates = states,
        now = { now },
        io = dispatcher,
    ).also { backgroundScope.launch { it.cleanup.collect { } } }

    private fun LibraryViewModel.ids(group: CleanupGroup) =
        cleanup.value.firstOrNull { it.group == group }?.ids?.map { it.value }

    @Test
    fun `the groups come from the files, their play state and the queue together`() = runTest(dispatcher) {
        onDisk("old", downloadedDaysAgo = 60, openedDaysAgo = 45)
        onDisk("fresh", downloadedDaysAgo = 2)
        onDisk("big", downloadedDaysAgo = 1, openedDaysAgo = 1)
        queue.enqueue(episode("fresh"))
        queue.enqueue(episode("big"))
        states.value = mapOf(MediaItemId("fresh") to PlayState.Played)

        val model = model()
        advanceUntilIdle()

        assertEquals(listOf("old"), model.ids(CleanupGroup.NOT_OPENED))
        assertEquals(listOf("fresh"), model.ids(CleanupGroup.FINISHED))
        assertEquals(listOf("big"), model.ids(CleanupGroup.BIG))
        assertEquals(listOf("old"), model.ids(CleanupGroup.NOT_QUEUED))
    }

    @Test
    fun `deleting a suggested group deletes the files and takes them out of the queue`() = runTest(dispatcher) {
        onDisk("a", downloadedDaysAgo = 1)
        onDisk("b", downloadedDaysAgo = 1)
        onDisk("keep", downloadedDaysAgo = 1)
        listOf("a", "b", "keep").forEach { queue.enqueue(episode(it)) }
        val model = model()
        advanceUntilIdle()

        model.deleteSuggested(CleanupGroup.FINISHED, listOf(MediaItemId("a"), MediaItemId("b")))
        advanceUntilIdle()

        assertEquals(listOf("keep"), queue.state.value.entries.map { it.item.item.id.value })
        assertEquals(listOf("keep"), downloads.observeDownloaded().first().map { it.item.id.value })
    }

    @Test
    fun `an unticked file is neither deleted nor taken out of the queue`() = runTest(dispatcher) {
        onDisk("a", downloadedDaysAgo = 1)
        onDisk("b", downloadedDaysAgo = 1)
        listOf("a", "b").forEach { queue.enqueue(episode(it)) }
        val model = model()
        advanceUntilIdle()

        model.deleteSuggested(CleanupGroup.BIG, listOf(MediaItemId("b")))
        advanceUntilIdle()

        assertEquals(listOf("a"), queue.state.value.entries.map { it.item.item.id.value })
        assertEquals(listOf("a"), downloads.observeDownloaded().first().map { it.item.id.value })
    }

    private companion object {
        const val SMALL = 10L * 1024 * 1024
        const val BIG = 900L * 1024 * 1024
    }
}
