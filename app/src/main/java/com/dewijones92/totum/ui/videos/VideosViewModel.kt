package com.dewijones92.totum.ui.videos

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.ChannelVideosResult
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.data.feed.NoOpFeedCache
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.GroupServices
import com.dewijones92.totum.di.YouTubeAccountServices
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.interleaveShorts
import com.dewijones92.totum.domain.videoFileOrNull
import com.dewijones92.totum.innertube.feeds.AccountFeed
import com.dewijones92.totum.innertube.feeds.FeedResult
import com.dewijones92.totum.innertube.feeds.FeedVideo
import com.dewijones92.totum.innertube.subscriptions.SubscribedChannel
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.MediaSort
import com.dewijones92.totum.ui.common.TrackedViewModel
import com.dewijones92.totum.ui.common.toMediaItem
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.SubscriptionShorts
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The Videos tab's one view-model: several small user actions (play, download,
// subscribe, sort, refresh, feed selection) push it just past the counter; they
// are all thin and cohesive, so splitting would only scatter the screen's logic.
@Suppress("TooManyFunctions")
class VideosViewModel(
    private val channels: ChannelRepository,
    private val accountSubscriptions: AccountSubscriptions,
    private val queue: PlaybackQueue,
    private val downloads: DownloadManager,
    private val youtube: YouTubeAccountServices,
    private val groups: GroupServices,
    private val feedCache: FeedCache = NoOpFeedCache,
    /**
     * Fetches the Shorts belonging to the channels a feed page showed, so Shorts appear in the
     * feed rather than behind their own button. Null disables it, which is what previews and the
     * feed tests want — the feed's own behaviour must not depend on a second network call.
     */
    private val subscriptionShorts: SubscriptionShorts? = null,
) : TrackedViewModel("videos") {

    data class UiState(
        /** The signed-in account's subscribed channels, read live from YouTube. */
        val subscriptions: List<MediaSource.VideoChannel> = emptyList(),
        val videos: List<MediaItem> = emptyList(),
        val subscribing: Subscribing = Subscribing.Idle,
        /** watchUrl currently being resolved for playback, if any. */
        val resolving: String? = null,
        val downloadStates: Map<MediaItemId, DownloadState> = emptyMap(),
        /** Account feeds are offered only when signed in. */
        val signedIn: Boolean = false,
        val selected: FeedChoice? = null,
        /** Dewi's own groups, offered as chips beside the account feeds. */
        val groups: List<SourceGroup> = emptyList(),
        val feedLoading: Boolean = false,
        val feedError: Boolean = false,
        /** Pull-to-refresh in progress (keeps content visible, unlike [feedLoading]). */
        val refreshing: Boolean = false,
        /** Whether a further page exists, so the list knows to keep asking. */
        val canLoadMore: Boolean = false,
        /** A further page is in flight — drives the footer spinner and blocks re-asking. */
        val loadingMore: Boolean = false,
        val sort: MediaSort = MediaSort.DEFAULT,
    )

    sealed interface Subscribing {
        data object Idle : Subscribing
        data object InProgress : Subscribing
        data object Done : Subscribing

        sealed interface Error : Subscribing {
            data object InvalidUrl : Error
            data object Network : Error
            data object NotAChannel : Error
            data object AlreadySubscribed : Error
        }
    }

    private val subscribing = MutableStateFlow<Subscribing>(Subscribing.Idle)
    private val resolving = MutableStateFlow<String?>(null)
    private val refreshing = MutableStateFlow(false)
    private val sort = MutableStateFlow(MediaSort.DEFAULT)

    private data class FeedState(
        val selected: FeedChoice? = null,
        val loading: Boolean = false,
        val videos: List<MediaItem> = emptyList(),
        val error: Boolean = false,
        /** Where the next page starts; null once the feed has no more to give. */
        val next: PageToken? = null,
        val loadingMore: Boolean = false,
    )

    private val feedState = MutableStateFlow(FeedState())

    init {
        // React to sign-in state — including signing in mid-session — off the
        // one live subscriptions seam: load the subscriptions feed (recent
        // uploads) as the default when signed in, clear it when signed out.
        viewModelScope.launch {
            accountSubscriptions.signedIn.collect { signed ->
                when {
                    signed -> if (feedState.value.selected == null) {
                        select(FeedChoice.Account(AccountFeed.SUBSCRIPTIONS))
                    }
                    // Only an ACCOUNT feed depends on being signed in. A group can be all
                    // podcasts, which need no account at all, so clearing everything here
                    // would empty a feed that was working perfectly well.
                    feedState.value.selected is FeedChoice.Group -> Diag.log(
                        "feed",
                        "signed out, but a group is showing — leaving it be",
                    )
                    else -> feedState.value = FeedState()
                }
            }
        }
    }

    private val flags = combine(subscribing, resolving, refreshing) { sub, res, ref -> Triple(sub, res, ref) }

    /** What the feed area shows, gathered so the main combine stays inside its arity. */
    private data class FeedView(
        val feed: FeedState,
        val signedIn: Boolean,
        val sort: MediaSort,
        val groups: List<SourceGroup>,
    )

    private val feedView = combine(
        feedState,
        accountSubscriptions.signedIn,
        sort,
        groups.store.observeGroups(),
        ::FeedView,
    )

    val uiState: StateFlow<UiState> = combine(
        accountSubscriptions.channels,
        downloads.observeDownloads(),
        flags,
        feedView,
    ) { subs, downloadStates, (subscribing, resolving, refreshing), view ->
        UiState(
            subscriptions = subs,
            videos = view.sort.apply(view.feed.videos),
            subscribing = subscribing,
            resolving = resolving,
            downloadStates = downloadStates,
            signedIn = view.signedIn,
            selected = view.feed.selected,
            groups = view.groups,
            feedLoading = view.feed.loading,
            feedError = view.feed.error,
            refreshing = refreshing,
            canLoadMore = view.feed.next != null,
            loadingMore = view.feed.loadingMore,
            sort = view.sort,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState())

    fun setSort(order: MediaSort) {
        sort.value = order
    }

    fun select(choice: FeedChoice?) {
        feedState.update { it.copy(selected = choice, error = false) }
        if (choice == null) return
        Diag.log("feed", "selected ${choice.describe()}")
        viewModelScope.launch {
            feedState.update { it.copy(loading = true) }
            showCached(choice)
            feedState.value = when (choice) {
                is FeedChoice.Account -> accountFeedState(choice)
                // A group is fetched whole — each member gives what it has, and there is no
                // continuation to follow — so it lands with no paging token by design.
                is FeedChoice.Group -> {
                    val videos = groups.feed.itemsFor(choice.group)
                    if (videos.isNotEmpty()) feedCache.save(choice.cacheKey(), videos)
                    FeedState(selected = choice, loading = false, videos = videos)
                }
            }
        }
    }

    /**
     * Puts the last-known contents of [choice] on screen while the network is asked.
     *
     * The gap this fills is measured, not assumed: every launch showed an empty Videos tab —
     * `[place] videos entered … videos=0` — and did not fill it for about 1.2 seconds.
     *
     * Dropped if the feed has moved on while this was reading, and never allowed to overwrite
     * content already showing: a cache is there to fill a blank, never to replace something
     * fresher. `loading` stays true, so pull-to-refresh and the spinner still say work is in
     * flight rather than pretending the stale list is the answer.
     */
    private suspend fun showCached(choice: FeedChoice) {
        val cached = feedCache.items(choice.cacheKey())
        if (cached.isEmpty()) return
        feedState.update { current ->
            if (current.selected != choice || current.videos.isNotEmpty()) {
                current
            } else {
                Diag.log("feed", "showing ${cached.size} cached items for ${choice.describe()} while it loads")
                current.copy(videos = cached)
            }
        }
    }

    /** One key per feed, so account feeds and groups share the cache without colliding. */
    private fun FeedChoice.cacheKey(): String = when (this) {
        is FeedChoice.Account -> feed.name
        is FeedChoice.Group -> "group:${group.id.value}"
    }

    private suspend fun accountFeedState(choice: FeedChoice.Account): FeedState =
        when (val result = loadFeed(choice.feed)) {
            is FeedResult.Success -> {
                val videos = result.page.items.map { it.toMediaItem(choice.feed) }
                // Saved AFTER a successful fetch only, so a failure never overwrites a good
                // cache with nothing — the launch after an offline start would then be blank
                // again, which is the bug this exists to fix.
                feedCache.save(choice.cacheKey(), videos)
                // AFTER the state is returned, never before: Shorts are N extra requests and the
                // feed must appear at the speed it appears today. They are threaded in when they
                // land. Dewi's call when choosing between "all at once, slower" and this.
                fetchShortsFor(choice, videos)
                FeedState(selected = choice, loading = false, videos = videos, next = result.page.next)
            }
            FeedResult.SignedOut -> {
                // Token died mid-session — re-check, which clears signedIn app-wide. Only the
                // boolean is wanted here; refreshing fetched 1,594 channels to learn it.
                accountSubscriptions.recheckSignedIn()
                FeedState()
            }
            is FeedResult.Failure -> FeedState(choice, loading = false, error = true)
        }

    /**
     * Asks the page's channels for their Shorts and threads them in when they arrive.
     *
     * A separate coroutine so nothing waits on it, and keyed on the feed that asked: switching
     * tabs while it is in flight must not drop a dozen Shorts into a feed they do not belong to.
     */
    private fun fetchShortsFor(choice: FeedChoice.Account, videos: List<MediaItem>) {
        val shorts = subscriptionShorts ?: return
        shortsJob?.cancel()
        shortsJob = viewModelScope.launch {
            val fetched = runCatching { shorts.forFeed(videos) }.getOrElse {
                Diag.warn("shorts", "could not fetch Shorts for ${choice.describe()}", it)
                return@launch
            }
            if (fetched.isEmpty()) return@launch
            feedState.update { state ->
                // Only if the user is still looking at the feed that asked. Otherwise the Shorts
                // belong to a list nobody is showing any more.
                if (state.selected != choice) {
                    Diag.log("shorts", "dropping ${fetched.size} Short(s) — the feed changed while they loaded")
                    state
                } else {
                    Diag.log("shorts", "threading ${fetched.size} Short(s) into ${choice.describe()}")
                    state.copy(videos = interleaveShorts(state.videos, fetched))
                }
            }
        }
    }

    private var shortsJob: Job? = null

    private fun FeedChoice.describe(): String = when (this) {
        is FeedChoice.Account -> feed.name
        is FeedChoice.Group -> "group \"${group.name}\" (${group.members.size} sources)"
    }

    /**
     * Pull-to-refresh: reload the subscription list and re-fetch the current
     * feed, keeping the visible content until the new data arrives (a transient
     * failure is swallowed rather than replacing the list with an error).
     */
    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            // Pull-to-refresh means "get me the current truth", so the freshness window
            // does not apply.
            accountSubscriptions.refresh(force = true)
            when (val choice = feedState.value.selected) {
                is FeedChoice.Account -> refreshAccountFeed(choice.feed)
                is FeedChoice.Group -> feedState.update {
                    it.copy(videos = groups.feed.itemsFor(choice.group), error = false)
                }
                null -> Unit
            }
            refreshing.value = false
        }
    }

    /**
     * Loads the next page of the current feed, appending it. Guarded so scrolling
     * cannot fire overlapping requests, and so it no-ops once the feed is exhausted —
     * the list asks whenever it nears the end, and asking is cheap only if repeats are.
     */
    private suspend fun refreshAccountFeed(feed: AccountFeed) {
        when (val result = loadFeed(feed)) {
            is FeedResult.Success -> {
                val items = result.page.items.map { it.toMediaItem(feed) }
                // A refresh replaces the feed, so paging restarts from this page's token —
                // keeping the old one would append pages that continue a list the user can
                // no longer see.
                feedState.update { it.copy(videos = items, error = false, next = result.page.next) }
            }
            FeedResult.SignedOut -> accountSubscriptions.recheckSignedIn()
            is FeedResult.Failure -> Unit // keep what's shown
        }
    }

    fun loadMore() {
        val state = feedState.value
        // Only an account feed pages; a group is already whole, and `next` is null for one
        // anyway — this says so out loud rather than relying on that.
        val feed = (state.selected as? FeedChoice.Account)?.feed ?: return
        val after = state.next ?: return
        if (state.loadingMore || state.loading) return
        feedState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            when (val result = loadFeed(feed, after)) {
                is FeedResult.Success -> feedState.update { current ->
                    Diag.log(
                        "feed",
                        "$feed page +${result.page.items.size} (had ${current.videos.size}) " +
                            "more=${result.page.hasMore}",
                    )
                    // Dedupe: YouTube does return overlapping pages, and a duplicate id
                    // in a LazyColumn key is a crash, not a cosmetic problem.
                    val existing = current.videos.map { it.id }.toSet()
                    val fresh = result.page.items.map { it.toMediaItem(feed) }.filter { it.id !in existing }
                    current.copy(
                        videos = current.videos + fresh,
                        next = result.page.next,
                        loadingMore = false,
                    )
                }
                FeedResult.SignedOut -> {
                    accountSubscriptions.recheckSignedIn()
                    feedState.value = FeedState()
                }
                // A failed page keeps the token, so scrolling again retries rather than
                // permanently ending the feed on one flaky request.
                is FeedResult.Failure -> feedState.update { it.copy(loadingMore = false) }
            }
        }
    }

    private suspend fun loadFeed(feed: AccountFeed, after: PageToken? = null): FeedResult = when (feed) {
        AccountFeed.RECOMMENDED -> youtube.feeds.recommended(after)
        AccountFeed.SUBSCRIPTIONS -> youtube.feeds.subscriptionsFeed(after)
        AccountFeed.WATCH_LATER -> youtube.feeds.watchLater(after)
        AccountFeed.HISTORY -> youtube.feeds.history(after)
    }

    private fun FeedVideo.toMediaItem(feed: AccountFeed) = toMediaItem(SourceId("ytfeed:${feed.name}"))

    /**
     * Subscribes to a channel by URL — resolves it to a YouTube channel id and
     * subscribes live on the account (no local copy). The live list updates
     * optimistically, so the new channel appears in the row straight away.
     */
    fun subscribe(rawUrl: String) {
        val url = HttpUrl.parse(rawUrl)
        if (url == null) {
            subscribing.value = Subscribing.Error.InvalidUrl
            return
        }
        viewModelScope.launch {
            subscribing.value = Subscribing.InProgress
            subscribing.value = when (val result = channels.fetchChannelVideos(url)) {
                is ChannelVideosResult.Success -> subscribeResolved(result, fallback = url)
                is ChannelVideosResult.Failure.Network -> Subscribing.Error.Network
                is ChannelVideosResult.Failure.NotAChannel -> Subscribing.Error.NotAChannel
            }
        }
    }

    private suspend fun subscribeResolved(result: ChannelVideosResult.Success, fallback: HttpUrl): Subscribing {
        val canonical = SubscribedChannel.channelUrlFor(result.channelId) ?: fallback
        val source = MediaSource.VideoChannel(SourceId(canonical.value), result.title, canonical)
        if (accountSubscriptions.isSubscribed(source.id)) return Subscribing.Error.AlreadySubscribed
        accountSubscriptions.setSubscribed(source, subscribed = true)
        return Subscribing.Done
    }

    fun resetSubscribing() {
        subscribing.update { Subscribing.Idle }
    }

    /**
     * Plays the merged download when it exists (already SponsorBlock-clean, no
     * resolution needed), else resolves the stream and plays it. One decision,
     * one place — mirrors the podcasts pillar.
     *
     * Goes through the queue, so tapping something plays it *and* keeps whatever
     * was lined up behind it.
     */
    fun play(video: MediaItem) {
        // Only a full download stands in for the video; an audio-only one (the queue's
        // automatic fetch) would play sound with a blank picture.
        val local = uiState.value.downloadStates[video.id]?.videoFileOrNull()
        val handle = local?.let(PlayHandle::LocalVideo)
            ?: PlayHandle.Video(video.mediaUrl ?: return)
        viewModelScope.launch {
            resolving.value = video.mediaUrl?.value
            queue.playNow(PlayableItem(video, handle))
            resolving.value = null
        }
    }

    fun download(video: MediaItem) {
        viewModelScope.launch { downloads.download(video) }
    }

    fun deleteDownload(video: MediaItem) {
        viewModelScope.launch { downloads.delete(video.id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                VideosViewModel(
                    channels = container.channelRepository,
                    accountSubscriptions = container.accountSubscriptions,
                    queue = container.playbackQueue,
                    downloads = container.downloadManager,
                    youtube = YouTubeAccountServices(
                        account = container.youTubeAccount,
                        feeds = container.youTubeFeeds,
                        actions = container.youTubeActions,
                    ),
                    groups = GroupServices(container.sourceGroupStore, container.groupFeed),
                    feedCache = container.feedCache,
                    subscriptionShorts = SubscriptionShorts(container.youTubeChannel),
                )
            }
        }
    }
}
