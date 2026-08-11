package com.dewijones92.totum.ui.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.search.YtDlpVideoSearchSource
import com.dewijones92.totum.data.search.fake.InMemorySearchHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val engine = FakeYtDlpEngine()
    private val playback = FakePlaybackController()
    private val repository = FakePodcastRepository()

    private val podcastHit = SearchHit.Podcast(
        title = "In Our Time",
        subtitle = "BBC",
        artworkUrl = null,
        feedUrl = HttpUrl.of("https://podcasts.files.bbci.co.uk/b006qykl.rss"),
    )

    private var cannedSegments: List<SkipSegment> = emptyList()

    private fun viewModel(
        podcastSearch: SearchSource = SearchSource { _, _, _ -> SearchOutcome.Success(Page.last(listOf(podcastHit))) },
        videoSearch: SearchSource = YtDlpVideoSearchSource(engine),
        musicSearch: SearchSource = SearchSource { _, _, _ -> SearchOutcome.Success(Page.last(emptyList())) },
    ) = SearchViewModel(
        sources = SearchSources(podcasts = podcastSearch, videos = videoSearch, music = musicSearch),
        // No home server in these tests: the section is simply absent, which is the common case.
        torrents = null,
        podcastRepository = repository,
        // Search plays through the queue like every other screen now, so the test wires the
        // real queue (over a fake controller) instead of reaching for the launcher directly.
        queue = PlaybackQueue(
            playback,
            VideoPlaybackLauncher(
                VideoResolver(engine, SkipSegmentSource { cannedSegments }),
                playback,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
            InMemoryQueueStore(),
        ),
        history = InMemorySearchHistoryStore(),
    )

    /** The video titles currently on screen — one accessor, so the section's shape lives in one place. */
    private fun shownVideoTitles(model: SearchViewModel): List<String>? =
        (model.uiState.value.results as? Results.Loaded)?.videos?.itemsOrNull?.items?.map { it.title }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `search loads both sections`() = runTest(dispatcher) {
        engine.registerSearch("time", listOf(FakeYtDlpEngine.sampleSearchEntry()))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.search("time")
        advanceUntilIdle()

        val results = viewModel.uiState.value.results as Results.Loaded
        assertEquals(listOf(podcastHit), results.podcasts.itemsOrNull)
        assertEquals(1, results.videos.itemsOrNull!!.items.size)
        assertEquals("Sample result", results.videos.itemsOrNull!!.items[0].title)
    }

    @Test
    fun `typing drives a debounced search once the query is long enough`() = runTest(dispatcher) {
        engine.registerSearch("time", listOf(FakeYtDlpEngine.sampleSearchEntry()))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        // A single character stays Idle — we don't hammer the backends per keystroke.
        viewModel.onQueryChange("t")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.results is Results.Idle)

        // Enough characters: after the debounce, results load without an explicit submit.
        viewModel.onQueryChange("time")
        advanceUntilIdle()
        val results = viewModel.uiState.value.results as Results.Loaded
        assertEquals(listOf(podcastHit), results.podcasts.itemsOrNull)
        assertEquals(1, results.videos.itemsOrNull!!.items.size)
    }

    /** A source that hands out numbered pages, so paging can be driven to exhaustion. */
    private fun pagedVideos(pages: List<Page<SearchHit>>): SearchSource {
        var index = 0
        return SearchSource { _, _, after ->
            // A first search must not consume a continuation page.
            if (after == null) index = 0
            SearchOutcome.Success(pages.getOrElse(index++) { Page.empty() })
        }
    }

    private fun videoHit(id: String) = SearchHit.Video(
        title = id,
        subtitle = null,
        artworkUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        durationSeconds = null,
    )

    @Test
    fun `loading more appends the next page and keeps what is already shown`() = runTest(dispatcher) {
        val viewModel = viewModel(
            videoSearch = pagedVideos(
                listOf(
                    Page(listOf(videoHit("one")), PageToken("t1")),
                    Page.last(listOf(videoHit("two"))),
                ),
            ),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.search("time")
        advanceUntilIdle()
        assertEquals(listOf("one"), shownVideoTitles(viewModel))

        viewModel.loadMoreVideos()
        advanceUntilIdle()

        val results = viewModel.uiState.value.results as Results.Loaded
        assertEquals(listOf("one", "two"), results.videos.itemsOrNull!!.items.map { it.title })
        assertFalse("the last page must end the scroll", results.canLoadMore)
    }

    /** YouTube does return overlapping pages; a repeat must not double a row. */
    @Test
    fun `an overlapping page does not duplicate rows`() = runTest(dispatcher) {
        val viewModel = viewModel(
            videoSearch = pagedVideos(
                listOf(
                    Page(listOf(videoHit("one")), PageToken("t1")),
                    Page.last(listOf(videoHit("one"), videoHit("two"))),
                ),
            ),
        )
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.search("time")
        advanceUntilIdle()
        viewModel.loadMoreVideos()
        advanceUntilIdle()

        val results = viewModel.uiState.value.results as Results.Loaded
        assertEquals(listOf("one", "two"), results.videos.itemsOrNull!!.items.map { it.title })
    }

    @Test
    fun `a final page offers nothing more to load`() = runTest(dispatcher) {
        val viewModel = viewModel(videoSearch = pagedVideos(listOf(Page.last(listOf(videoHit("only"))))))
        backgroundScope.launch { viewModel.uiState.collect {} }
        viewModel.search("time")
        advanceUntilIdle()

        val results = viewModel.uiState.value.results as Results.Loaded
        assertFalse(results.canLoadMore)

        // Asking anyway is a no-op rather than an error or a duplicate fetch.
        viewModel.loadMoreVideos()
        advanceUntilIdle()
        assertEquals(listOf("only"), shownVideoTitles(viewModel))
    }

    @Test
    fun `one backend failing does not hide the other`() = runTest(dispatcher) {
        engine.registerSearch("time", listOf(FakeYtDlpEngine.sampleSearchEntry()))
        val viewModel = viewModel(podcastSearch = SearchSource { _, _, _ -> SearchOutcome.Failure("down") })
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.search("time")
        advanceUntilIdle()

        val results = viewModel.uiState.value.results as Results.Loaded
        assertTrue("only the podcast section should be marked failed", results.podcasts is SearchSection.Failed)
        assertEquals(1, results.videos.itemsOrNull!!.items.size)
    }

    @Test
    fun `subscribing from search lands in subscriptions and marks the hit`() = runTest(dispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.subscribe(podcastHit)
        advanceUntilIdle()

        assertTrue(podcastHit.feedUrl.value in viewModel.uiState.value.subscribedFeeds)
    }

    @Test
    fun `playing a video hit resolves the stream and its skip segments`() = runTest(dispatcher) {
        val entry = FakeYtDlpEngine.sampleSearchEntry(id = "v9", title = "Playable")
        engine.registerMedia(entry.watchUrl, FakeYtDlpEngine.sampleMetadata(id = "v9"))
        cannedSegments = listOf(SkipSegment(10.seconds, 25.seconds))
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.playVideo(
            SearchHit.Video(
                title = entry.title,
                subtitle = null,
                artworkUrl = null,
                watchUrl = entry.watchUrl,
                durationSeconds = 90,
            ),
        )
        advanceUntilIdle()

        val playing = playback.state.value
        assertNotNull(playing)
        assertEquals("Sample video", playing?.title)
        assertEquals(cannedSegments, playback.lastSkipSegments)
        assertEquals(false, viewModel.uiState.value.resolveFailed)
    }

    @Test
    fun `unresolvable video surfaces a failure flag`() = runTest(dispatcher) {
        val viewModel = viewModel()
        backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.playVideo(
            SearchHit.Video(
                title = "Gone",
                subtitle = null,
                artworkUrl = null,
                watchUrl = HttpUrl.of("https://example.com/watch?v=gone"),
                durationSeconds = null,
            ),
        )
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.resolveFailed)
    }
}
