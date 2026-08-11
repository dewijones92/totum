package com.dewijones92.totum.ui.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.search.fake.InMemorySearchHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import com.dewijones92.totum.innertube.history.fake.FakeYouTubeWatchHistory
import com.dewijones92.totum.playback.fake.FakePlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.search.SearchViewModel.Results
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Results appearing as each source answers, rather than when the slowest does.
 *
 * Dewi, 2026-08-07: *"the search in the app is quite slow as its blocked by the torrent search"*. It
 * was, and not because anything ran in series: all three sources were started together and then
 * `toLoaded(podcasts.await(), videos.await(), torrents.await())` threw the concurrency away by
 * waiting for every one of them. Torrent search goes out to Prowlarr and through FlareSolverr —
 * seconds at best, and off the home network it is the full timeout — so a YouTube search that had
 * finished in a moment sat behind it with nowhere to be shown.
 *
 * These hold the behaviour at the level it lives: what the UI is told, and when. The sources here
 * are hand-held with [CompletableDeferred] so "the torrent search has not answered yet" is a state
 * the test controls rather than a race it hopes for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchStreamsPerSectionTest {

    private companion object {
        /** Comfortably past the 300ms search debounce, and nowhere near any section timeout. */
        const val PAST_DEBOUNCE_MS = 400L
    }

    private val videoGate = CompletableDeferred<SearchOutcome>()
    private val musicGate = CompletableDeferred<SearchOutcome>()
    private val podcastGate = CompletableDeferred<SearchOutcome>()
    private val torrentGate = CompletableDeferred<SearchOutcome>()

    private fun song(title: String) = SearchHit.Song(
        title = title,
        subtitle = "Nina Simone • I Put A Spell On You",
        artworkUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=${title.filter { it.isLetterOrDigit() }}"),
        durationSeconds = 174,
        artist = "Nina Simone",
        album = "I Put A Spell On You",
    )

    private fun video(id: String) = SearchHit.Video(
        title = "Video $id",
        subtitle = null,
        artworkUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        durationSeconds = 60,
    )

    private fun torrent(name: String) = SearchHit.Torrent(
        title = name,
        subtitle = null,
        artworkUrl = null,
        magnet = "magnet:?xt=urn:btih:$name",
        seeders = 10,
        sizeBytes = 1_000,
        indexer = "test",
    )

    private fun podcast(name: String) = SearchHit.Podcast(
        title = name,
        subtitle = null,
        artworkUrl = null,
        feedUrl = HttpUrl.of("https://example.test/${name.replace(' ', '-')}.xml"),
    )

    private fun page(vararg hits: SearchHit) = SearchOutcome.Success(Page.last(hits.toList()))

    private val dispatcher = StandardTestDispatcher()
    private val playback = FakePlaybackController()
    private val engine = FakeYtDlpEngine()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(withHomeServer: Boolean = false) = SearchViewModel(
        sources = SearchSources(
            podcasts = SearchSource { _, _, _ -> podcastGate.await() },
            videos = SearchSource { _, _, _ -> videoGate.await() },
            music = SearchSource { _, _, _ -> musicGate.await() },
        ),
        // No home server by default: the torrent section is then Absent, which one case asserts.
        torrents = if (!withHomeServer) {
            null
        } else {
            TorrentServices(SearchSource { _, _, _ -> torrentGate.await() }, FakeHomeTorrentServer())
        },
        podcastRepository = FakePodcastRepository(),
        queue = PlaybackQueue(
            playback,
            VideoPlaybackLauncher(
                VideoResolver(engine, SkipSegmentSource { emptyList() }),
                playback,
                FakeYouTubeWatchHistory(),
                InMemoryPlayHistoryStore(),
            ),
            CoroutineScope(dispatcher),
            InMemoryQueueStore(),
        ),
        history = InMemorySearchHistoryStore(),
    )

    /**
     * Starts a search and lets the debounce elapse — and NOTHING more.
     *
     * Deliberately not `advanceUntilIdle`: virtual time would run past the per-section timeout and
     * every source would be reported as not having answered before the test could answer for it.
     * The whole point here is holding a source open, so the clock has to be moved deliberately.
     */
    private fun TestScope.startSearch(model: SearchViewModel, query: String) {
        model.onQueryChange(query)
        advanceTimeBy(PAST_DEBOUNCE_MS)
        runCurrent()
    }

    /** THE POINT. Videos are on screen while the other sources are still out. */
    @Test
    fun `videos are shown while the other sources are still searching`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        startSearch(model, "ceuta")

        videoGate.complete(page(video("a"), video("b")))
        runCurrent()

        val results = model.uiState.value.results as Results.Loaded
        assertEquals(
            "the videos must be on screen without waiting for anything else",
            listOf("Video a", "Video b"),
            results.videos.itemsOrNull?.items?.map { it.title },
        )
        assertTrue("the podcast section must say it is still looking", results.podcasts.isSearching)
        assertTrue("and the screen must know something is outstanding", results.stillSearching)
    }

    /**
     * The complaint, as an assertion: a slow section must not hold up a fast one.
     *
     * Before the fix the results stayed `Searching` — not `Loaded` with one section pending — until
     * every source had answered, so this is the difference between the old shape and the new one.
     */
    @Test
    fun `a source that has not answered does not hold back the ones that have`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        startSearch(model, "novara")

        podcastGate.complete(page(podcast("Novara FM")))
        runCurrent()

        val results = model.uiState.value.results
        assertTrue("results must already be Loaded, not still Searching", results is Results.Loaded)
        assertEquals(
            listOf("Novara FM"),
            (results as Results.Loaded).podcasts.itemsOrNull?.map { it.title },
        )
        assertTrue(results.videos.isSearching)
    }

    @Test
    fun `each section settles independently as its source answers`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        startSearch(model, "ceuta")

        videoGate.complete(page(video("a")))
        runCurrent()
        assertTrue((model.uiState.value.results as Results.Loaded).podcasts.isSearching)

        podcastGate.complete(page(podcast("A Feed")))
        musicGate.complete(page(song("Feeling Good")))
        runCurrent()

        val results = model.uiState.value.results as Results.Loaded
        assertTrue("everything has answered", !results.stillSearching)
        assertEquals(1, results.videos.itemsOrNull?.items?.size)
        assertEquals(1, results.podcasts.itemsOrNull?.size)
        assertEquals(1, results.songs.itemsOrNull?.size)
    }

    /** One source failing still must not hide another, which was true before and stays true. */
    @Test
    fun `a failing source marks only its own section`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        startSearch(model, "ceuta")

        videoGate.complete(page(video("a")))
        podcastGate.complete(SearchOutcome.Failure("itunes said no"))
        runCurrent()

        val results = model.uiState.value.results as Results.Loaded
        assertEquals(SearchSection.Failed("itunes said no"), results.podcasts)
        assertEquals(1, results.videos.itemsOrNull?.items?.size)
    }

    /**
     * With no home server there is no torrent section — not an empty one, and not a failing one.
     *
     * Somebody who has never set one up would otherwise see a section permanently reporting a
     * problem they do not have.
     */
    @Test
    fun `with no home server the torrent section is absent rather than empty`() = runTest(dispatcher) {
        val model = model()
        backgroundScope.launch { model.uiState.collect {} }
        startSearch(model, "ceuta")

        val results = model.uiState.value.results as Results.Loaded
        assertEquals(SearchSection.Absent, results.torrents)
        assertTrue(
            "an absent section must not keep the screen waiting",
            !results.torrents.isSearching,
        )
    }
}
