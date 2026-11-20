package com.dewijones92.totum.ui.search

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.history.fake.InMemoryPlayHistoryStore
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.JellyfinApi
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.queue.fake.InMemoryQueueStore
import com.dewijones92.totum.data.search.HomeHoldings
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.search.YourPlace
import com.dewijones92.totum.data.search.YoursSearchSource
import com.dewijones92.totum.data.search.fake.InMemorySearchHistoryStore
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class YoursSearchTest {

    private val dispatcher = StandardTestDispatcher()
    private val playback = FakePlaybackController()
    private val engine = FakeYtDlpEngine()
    private val homeGate = CompletableDeferred<HomeHoldings>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun playable(id: String, title: String) = PlayableItem(
        MediaItem(
            id = MediaItemId(id),
            sourceId = SourceId("test"),
            title = title,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.test/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private val empty = SearchSource { _, _, _ -> SearchOutcome.Success(Page.last(emptyList())) }

    private fun model(yours: SearchSource?) = SearchViewModel(
        sources = SearchSources(empty, empty, empty, empty, empty, empty, empty, yours),
        torrents = null,
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

    private fun yoursSource(
        atHome: (suspend () -> HomeHoldings)? = { homeGate.await() },
        jellyfin: JellyfinApi? = null,
    ) = YoursSearchSource(
        onPhone = { listOf(DownloadedMedia(playable("torrent:a:1", "Spinal Tap II"), "/data/a.mkv", false)) },
        atHome = atHome,
        jellyfin = jellyfin,
        compute = dispatcher,
    )

    private fun TestScope.startSearch(model: SearchViewModel, query: String) {
        backgroundScope.launch { model.uiState.collect {} }
        model.onQueryChange(query)
        advanceTimeBy(400)
        runCurrent()
    }

    private fun SearchViewModel.loaded() = uiState.value.results as Results.Loaded

    @Test
    fun `with no source for it the Yours section is absent and so is its tab`() = runTest(dispatcher) {
        val model = model(yours = null)

        startSearch(model, "spinal tap")

        assertEquals(SearchSection.Absent, model.loaded().yours)
        assertTrue(searchTabsFor(model.loaded(), "spinal tap").none { it.tab == SearchTab.YOURS })
    }

    @Test
    fun `the phone's match is on screen and Yours is the tab shown while the Pi is still out`() = runTest(dispatcher) {
        val model = model(yoursSource())

        startSearch(model, "spinal tap")

        val found = model.loaded().yours as SearchSection.Found
        assertEquals(listOf("Spinal Tap II"), found.items.map { it.title })
        assertEquals(setOf(YourPlace.PHONE), found.items.single().places)
        assertEquals(listOf(YoursSearchSource.HOME), found.stillWaitingFor)
        val tabs = searchTabsFor(model.loaded(), "spinal tap")
        assertEquals(SearchTab.YOURS, tabs.first().tab)
        assertEquals(SearchTab.YOURS, defaultSearchTab(tabs))
        assertTrue(tabs.first().partial)
    }

    @Test
    fun `a Pi that is not reachable leaves the phone's match and says so`() = runTest(dispatcher) {
        val model = model(yoursSource(atHome = { HomeHoldings.NotRead }))

        startSearch(model, "spinal tap")

        val found = model.loaded().yours as SearchSection.Found
        assertEquals(listOf("Spinal Tap II"), found.items.map { it.title })
        assertEquals(listOf(YoursSearchSource.HOME_UNREACHABLE), found.notes)
        assertTrue(found.stillWaitingFor.isEmpty())
    }

    @Test
    fun `nothing matching and a Pi out of reach still gets a tab, so the search does not look empty by mistake`() =
        runTest(dispatcher) {
            val model = model(yoursSource(atHome = { HomeHoldings.NotRead }))

            startSearch(model, "zzzzzz")

            val tab = searchTabsFor(model.loaded(), "zzzzzz").first { it.tab == SearchTab.YOURS }
            assertEquals(0, tab.count)
            assertTrue(tab.empty)
        }

    @Test
    fun `nothing matching and nothing wrong adds no tab`() = runTest(dispatcher) {
        val model = model(yoursSource(atHome = null))

        startSearch(model, "zzzzzz")

        assertTrue(searchTabsFor(model.loaded(), "zzzzzz").none { it.tab == SearchTab.YOURS })
    }

    @Test
    fun `a Jellyfin film is listed with the others, once the Pi answers`() = runTest(dispatcher) {
        val jelly = object : JellyfinApi {
            override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> = error("not browsed")
            override suspend fun search(term: String): HomeAnswer<JellyfinListing> = HomeAnswer.Ok(
                JellyfinListing(
                    null,
                    "🔎",
                    listOf(JellyfinItem("0123456789abcdef", "This Is Spinal Tap", JellyfinKind.MOVIE, year = 1984)),
                    1,
                    null,
                    search = term,
                ),
            )

            override fun streamUrl(item: JellyfinItem): HttpUrl = HttpUrl.of("https://totum.example/fetch/x.mkv")
        }
        val model = model(yoursSource(atHome = null, jellyfin = jelly))

        startSearch(model, "spinal tap")

        val rows = (model.loaded().yours as SearchSection.Found).items
        assertEquals(listOf("Spinal Tap II", "This Is Spinal Tap"), rows.map { it.title })
        assertEquals(setOf(YourPlace.JELLYFIN), rows.last().places)
    }

    @Test
    fun `a Jellyfin series is a row in the tab and counts in its number`() = runTest(dispatcher) {
        val jelly = object : JellyfinApi {
            override suspend fun browse(parent: String?, start: Int): HomeAnswer<JellyfinListing> = error("not browsed")
            override suspend fun search(term: String): HomeAnswer<JellyfinListing> = HomeAnswer.Ok(
                JellyfinListing(
                    null,
                    "🔎",
                    listOf(JellyfinItem("0123456789abcd77", "Spinal Tap Diaries", JellyfinKind.SERIES, year = 1999)),
                    1,
                    null,
                    search = term,
                ),
            )

            override fun streamUrl(item: JellyfinItem): HttpUrl = HttpUrl.of("https://totum.example/fetch/x.mkv")
        }
        val model = model(yoursSource(atHome = null, jellyfin = jelly))

        startSearch(model, "spinal tap")

        val rows = (model.loaded().yours as SearchSection.Found).items
        val series = rows.filterIsInstance<SearchHit.YoursSeries>().single()
        assertEquals("0123456789abcd77", series.id)
        assertEquals(listOf("Spinal Tap II", "Spinal Tap Diaries"), rows.map { it.title })
        assertEquals(2, searchTabsFor(model.loaded(), "spinal tap").first { it.tab == SearchTab.YOURS }.count)
    }

    @Test
    fun `tapping a row plays it through the queue`() = runTest(dispatcher) {
        val model = model(yoursSource(atHome = null))
        startSearch(model, "spinal tap")
        val hit = (model.loaded().yours as SearchSection.Found).items.filterIsInstance<SearchHit.Yours>().single()
        assertNull(playback.state.value)

        model.playYours(hit)
        runCurrent()

        assertNotNull(playback.state.value)
        assertEquals("Spinal Tap II", playback.state.value?.title)
    }

    @Test
    fun `a Yours hit is carried as the SearchHit it is`() {
        val hit: SearchHit = SearchHit.Yours(playable("x", "Heat"), setOf(YourPlace.HOME))

        assertEquals("Heat", hit.title)
        assertNull(hit.artworkUrl)
    }
}
