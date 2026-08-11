package com.dewijones92.totum.ui.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.append
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.search.SearchHistoryStore
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchQuery
import com.dewijones92.totum.data.search.SearchSection
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.search.asSection
import com.dewijones92.totum.data.torrent.HomeTorrentServer
import com.dewijones92.totum.data.torrent.TorrentEpisodes
import com.dewijones92.totum.data.torrent.TorrentPlayables
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.TrackedViewModel
import com.dewijones92.totum.ui.common.toMediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The two halves of the home-server feature, together because neither is useful alone: search
 * with no server cannot play what it finds, and a server with no search has nothing to play.
 * Grouping them also keeps the view model's dependencies down to the things it actually has.
 */
class TorrentServices(val search: SearchSource, val server: HomeTorrentServer) {
    companion object {
        /** Null unless BOTH are configured, so a half-set-up server is absent rather than odd. */
        fun from(container: AppContainer): TorrentServices? {
            val search = container.torrentSearchSource ?: return null
            val server = container.homeTorrentServer ?: return null
            return TorrentServices(search, server)
        }
    }
}

@Suppress("TooManyFunctions") // One method per user action on a screen with several sections.
class SearchViewModel(
    private val sources: SearchSources,
    /** Null when no home server is configured — the section is then absent, not broken. */
    private val torrents: TorrentServices?,
    private val podcastRepository: PodcastRepository,
    private val queue: PlaybackQueue,
    private val history: SearchHistoryStore,
) : TrackedViewModel("search") {

    data class UiState(
        val results: Results = Results.Idle,
        /** Feed URLs already subscribed, so podcast hits render as such. */
        val subscribedFeeds: Set<String> = emptySet(),
        /** Recent searches, offered in the idle state. */
        val history: List<String> = emptyList(),
        /** Watch URL currently being resolved for playback, if any. */
        val resolving: String? = null,
        val resolveFailed: Boolean = false,
    )

    sealed interface Results {
        data object Idle : Results
        data object Searching : Results

        /**
         * Sections are independent — in failure and, since 2026-08-07, in TIME.
         *
         * Each arrives on its own: whichever source answers first is on screen while the others are
         * still out. Before this the screen waited for all three, so every search cost as much as
         * the torrent search, which goes through Prowlarr and FlareSolverr and is seconds at best.
         */
        data class Loaded(
            val podcasts: SearchSection<List<SearchHit.Podcast>>,
            /** Carries its own continuation, so the section knows whether more exists. */
            val videos: SearchSection<Page<SearchHit.Video>>,
            val songs: SearchSection<List<SearchHit.Song>> = SearchSection.Searching,
            /** [SearchSection.Absent] when no home server is set up, which is not a failure. */
            val torrents: SearchSection<List<SearchHit.Torrent>>,
            val loadingMore: Boolean = false,
        ) : Results {
            val canLoadMore: Boolean get() = videos.itemsOrNull?.hasMore == true

            /** True while any section is still out — drives the one thin bar at the top. */
            val stillSearching: Boolean
                get() = podcasts.isSearching || videos.isSearching || songs.isSearching || torrents.isSearching
        }
    }

    private val playAttempt = MutableStateFlow(PlayAttempt())

    private data class PlayAttempt(val resolving: String? = null, val failed: Boolean = false)

    /** The current query text; every keystroke and explicit submit sets it. */
    private val typed = MutableStateFlow("")

    /**
     * The one search stream driving search-as-you-type: typing is debounced,
     * [distinctUntilChanged] avoids re-running an unchanged query, and
     * [transformLatest] cancels any in-flight search when the query changes.
     * Below [MIN_QUERY_LENGTH] the results reset to Idle rather than hammering
     * the backends on a single keystroke.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private val searched: Flow<Results> = typed
        .debounce(DEBOUNCE_MILLIS)
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { raw ->
            if (raw.length < MIN_QUERY_LENGTH) {
                activeQuery = null
                emit(Results.Idle)
            } else {
                emit(Results.Searching)
                val query = SearchQuery(raw)
                activeQuery = query
                emitAll(searchStream(query))
            }
        }

    /*
     * Held rather than derived, because "load more" appends to what is already on screen
     * — a pure derivation of the query text has nowhere to put page two. The typed flow
     * feeds this; loadMoreVideos appends to it.
     */
    private val results = MutableStateFlow<Results>(Results.Idle)

    /** The query the current results belong to, so a continuation asks about the right one. */
    private var activeQuery: SearchQuery? = null

    init {
        viewModelScope.launch { searched.collect { results.value = it } }
    }

    val uiState: StateFlow<UiState> = combine(
        results,
        podcastRepository.observeSubscriptions().map { subscriptions ->
            subscriptions.mapNotNullTo(mutableSetOf()) {
                (it.source as? MediaSource.PodcastFeed)?.feedUrl?.value
            }
        },
        playAttempt,
        history.recent(),
    ) { results, subscribed, attempt, recent ->
        UiState(results, subscribed, recent, attempt.resolving, attempt.failed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    /** Called on every keystroke; the debounce lives in the results flow. */
    fun onQueryChange(rawQuery: String) {
        typed.value = rawQuery
    }

    /** Explicit submit (search button / IME action / history tap); records to history. */
    fun search(rawQuery: String) {
        typed.value = rawQuery
        val trimmed = rawQuery.trim()
        if (trimmed.length >= MIN_QUERY_LENGTH) {
            viewModelScope.launch { history.record(trimmed) }
        }
    }

    fun removeHistory(query: String) {
        viewModelScope.launch { history.remove(query) }
    }

    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }

    /**
     * Every source at once, emitting again each time one of them answers.
     *
     * The previous version also ran them concurrently and then awaited all three, which threw the
     * concurrency away: the screen showed nothing until the slowest finished. Here the first emission
     * is "all three still looking" and each source updates only its own slot, so YouTube results are
     * on screen while the home server is still being asked.
     *
     * `channelFlow` because the emissions come from three sibling coroutines; it closes when they
     * have all finished, and `transformLatest` upstream cancels the whole thing when the query
     * changes — so a search nobody is waiting for stops making requests.
     */
    private fun searchStream(query: SearchQuery): Flow<Results> = channelFlow {
        val startedAt = System.currentTimeMillis()
        val state = MutableStateFlow(
            Results.Loaded(
                podcasts = SearchSection.Searching,
                videos = SearchSection.Searching,
                songs = SearchSection.Searching,
                // Absent, not Searching: with no home server there is no section to wait for.
                torrents = if (torrents == null) SearchSection.Absent else SearchSection.Searching,
            ),
        )
        launch { state.collect { send(it) } }

        /**
         * One source, timed and logged, updating only its own slot.
         *
         * Bounded because a section that spins forever is worse than one that says it failed: the
         * home server is only reachable at home or on the VPN, and off it the request does not fail
         * fast, it hangs.
         */
        suspend fun <T> section(
            name: String,
            run: suspend () -> SearchOutcome?,
            into: (Results.Loaded, SearchSection<T>) -> Results.Loaded,
            select: (SearchOutcome.Success) -> T,
        ) {
            val outcome = withTimeoutOrNull(SECTION_TIMEOUT_MILLIS) { run() }
            val took = System.currentTimeMillis() - startedAt
            val result: SearchSection<T> = when (outcome) {
                null -> SearchSection.Failed("it did not answer within ${SECTION_TIMEOUT_MILLIS}ms")
                else -> outcome.asSection(select)
            }
            // Per section and with its own timing, because "search was slow" could never say WHICH
            // source was slow — the one question that mattered, and the reason this exists at all.
            Diag.log("search", "\"${query.value}\" $name after ${took}ms -> ${result.describe()}")
            state.update { into(it, result) }
        }

        launch {
            section<List<SearchHit.Podcast>>(
                name = "podcasts",
                run = { sources.podcasts.search(query, RESULTS_PER_SECTION, after = null) },
                into = { loaded, s -> loaded.copy(podcasts = s) },
                select = { it.page.items.filterIsInstance<SearchHit.Podcast>() },
            )
        }
        launch {
            section<Page<SearchHit.Video>>(
                name = "videos",
                run = { sources.videos.search(query, RESULTS_PER_SECTION, after = null) },
                into = { loaded, s -> loaded.copy(videos = s) },
                select = { it.page.videosOnly() },
            )
        }
        launch {
            section<List<SearchHit.Song>>(
                name = "songs",
                run = { sources.music.search(query, RESULTS_PER_SECTION, after = null) },
                into = { loaded, s -> loaded.copy(songs = s) },
                select = { it.page.items.filterIsInstance<SearchHit.Song>() },
            )
        }
        if (torrents != null) {
            launch {
                section<List<SearchHit.Torrent>>(
                    name = "torrents",
                    run = { torrents.search.search(query, RESULTS_PER_SECTION, null) },
                    into = { loaded, s -> loaded.copy(torrents = s) },
                    select = { it.page.items.filterIsInstance<SearchHit.Torrent>() },
                )
            }
        }
    }

    /** A section in one phrase, for the trail: what it is and how much it found. */
    private fun SearchSection<*>.describe(): String = when (this) {
        is SearchSection.Found -> when (val found = items) {
            is Page<*> -> "${found.items.size} (more=${found.hasMore})"
            is Collection<*> -> "${found.size}"
            else -> "found"
        }
        is SearchSection.Failed -> "FAILED: $detail"
        SearchSection.Searching -> "still searching"
        SearchSection.Absent -> "absent"
    }

    fun subscribe(hit: SearchHit.Podcast) {
        viewModelScope.launch {
            // Outcome surfaces via observeSubscriptions; failures leave the button active.
            podcastRepository.subscribe(hit.feedUrl)
        }
    }

    /** Resolves the hit's stream (shared launcher) and hands it to the shared player. */
    fun playVideo(hit: SearchHit.Video) {
        play(hit.watchUrl, hit.toMediaItem(AD_HOC_VIDEO_SOURCE))
    }

    /**
     * A song plays down EXACTLY the same path as a video, because that is what it is: a YouTube
     * video with music metadata. Anything else here would be a second playback route to keep in
     * step with the first.
     */
    fun playSong(hit: SearchHit.Song) {
        play(hit.watchUrl, hit.toMediaItem(AD_HOC_MUSIC_SOURCE))
    }

    private fun play(watchUrl: HttpUrl, item: MediaItem) {
        viewModelScope.launch {
            playAttempt.value = PlayAttempt(resolving = watchUrl.value)
            val played = queue.playNow(PlayableItem(item, PlayHandle.Video(watchUrl)))
            playAttempt.value = if (played) PlayAttempt() else PlayAttempt(failed = true)
        }
    }

    /**
     * Adds a torrent to the home server and queues everything playable in it.
     *
     * A season pack becomes one queue item per episode, which is why this is `playAll` with a
     * group rather than `playNow` with one thing — the queue then shows a header for the release
     * and can remove the whole season as a unit, exactly as it already does for a playlist.
     *
     * Preparing is the slow part (the server has to reach the swarm and read the metadata), so
     * the UI is told it is working rather than left silent for several seconds.
     */
    fun playTorrent(hit: SearchHit.Torrent) {
        val server = torrents?.server ?: return
        viewModelScope.launch {
            playAttempt.value = PlayAttempt(resolving = hit.title)
            val prepared = server.prepare(hit.magnet)
            val items = prepared?.let { TorrentPlayables.queueItems(server, it) }.orEmpty()
            if (items.isEmpty()) {
                Diag.warn("search", "\"${hit.title}\" had nothing playable in it")
                playAttempt.value = PlayAttempt(failed = true)
                return@launch
            }
            Diag.log("search", "queueing ${items.size} item(s) from \"${hit.title}\"")
            // Start the audio remux for what is about to play, without waiting for it. The
            // first HLS segment takes ~25s while ffmpeg waits on the swarm, so if this is left
            // until Listen is pressed it is 25 seconds of spinner; started here it overlaps the
            // queueing and the video that plays first.
            TorrentEpisodes.playableInOrder(prepared!!.files).firstOrNull()?.let { first ->
                launch { server.warmAudio(server.audioStream(prepared, first)) }
            }
            queue.playAll(items, QueueGroup(id = prepared.hash, title = prepared.name))
            playAttempt.value = PlayAttempt()
        }
    }

    /**
     * Fetches the next page of video results and appends it.
     *
     * Podcasts have no equivalent: the directory answers in one shot, which its source
     * states by returning a final page — so there is simply never a token to follow.
     */
    fun loadMoreVideos() {
        val current = results.value as? Results.Loaded ?: return
        if (current.loadingMore) return
        val shown = current.videos.itemsOrNull ?: return
        val token = shown.next ?: return
        val query = activeQuery ?: return
        results.value = current.copy(loadingMore = true)
        viewModelScope.launch {
            val outcome = sources.videos.search(query, RESULTS_PER_SECTION, token)
            // Re-read: a new query may have landed while this page was in flight, and
            // appending page two of the old search to it would be worse than dropping it.
            val latest = results.value as? Results.Loaded ?: return@launch
            if (activeQuery != query) return@launch
            results.value = when (outcome) {
                is SearchOutcome.Success -> {
                    val onScreen = latest.videos.itemsOrNull ?: return@launch
                    val grown = onScreen.append(outcome.page.videosOnly()) { it.watchUrl.value }
                    Diag.log(
                        "search",
                        "next page -> ${outcome.page.items.size} returned, " +
                            "${grown.items.size} total (more=${grown.hasMore})",
                    )
                    latest.copy(videos = SearchSection.Found(grown), loadingMore = false)
                }
                is SearchOutcome.Failure -> {
                    Diag.warn("search", "next page failed: ${outcome.detail}")
                    latest.copy(loadingMore = false)
                }
            }
        }
    }

    private fun Page<SearchHit>.videosOnly(): Page<SearchHit.Video> =
        Page(items.filterIsInstance<SearchHit.Video>(), next)

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val RESULTS_PER_SECTION = 8
        private const val DEBOUNCE_MILLIS = 300L

        /**
         * How long one section may take before it is reported as not having answered.
         *
         * Generous, because a slow answer is still worth having and the other sections are already
         * on screen by then — this is only to stop a section spinning for ever. The home server is
         * reachable only at home or on the VPN, and off it the request does not fail fast, it hangs.
         */
        private const val SECTION_TIMEOUT_MILLIS = 20_000L
        private const val MIN_QUERY_LENGTH = 2

        /** Ad-hoc plays from search don't belong to a subscribed source yet. */
        /** Shared with the search row, which builds the same MediaItem for its actions. */
        internal val AD_HOC_VIDEO_SOURCE = SourceId("search:ad-hoc-video")

        /**
         * Its own source id, so history and play-state can tell a song apart from a video of the
         * same thing. They ARE the same YouTube id, so the item id still matches — this only names
         * where it came from.
         */
        internal val AD_HOC_MUSIC_SOURCE = SourceId("search:ad-hoc-song")

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    sources = SearchSources(
                        podcasts = container.podcastSearchSource,
                        videos = container.videoSearchSource,
                        music = container.musicSearchSource,
                    ),
                    torrents = TorrentServices.from(container),
                    podcastRepository = container.podcastRepository,
                    queue = container.playbackQueue,
                    history = container.searchHistoryStore,
                )
            }
        }
    }
}
