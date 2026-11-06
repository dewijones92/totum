package com.dewijones92.totum.ui.channel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.ChannelVideosResult
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.GroupServices
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceGroup
import com.dewijones92.totum.domain.containsChannel
import com.dewijones92.totum.domain.findChannel
import com.dewijones92.totum.domain.listedOn
import com.dewijones92.totum.domain.youTubeChannelId
import com.dewijones92.totum.innertube.channel.ChannelPlaylists
import com.dewijones92.totum.innertube.channel.ChannelVideos
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.toMediaItem
import com.dewijones92.totum.ui.common.toMediaItemPage
import com.dewijones92.totum.video.AccountSubscriptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/**
 * Backs the tabbed channel page — Videos / Shorts / Playlists via InnerTube (so
 * videos carry their upload dates) — plus a subscribe toggle. Videos and Shorts
 * play through the same shared launcher every other screen uses. Channels reached
 * without a `UC…` id (a pasted handle) fall back to the yt-dlp uploads list for
 * the Videos tab.
 */
// The channel page's one view-model: tab selection, paging, play, download, subscribe.
// Each is thin and belongs to this screen, so splitting would scatter it (same reasoning
// as VideosViewModel).
@Suppress("TooManyFunctions")
class ChannelViewModel(
    private val source: MediaSource.VideoChannel,
    private val reader: ChannelReader,
    private val queue: PlaybackQueue,
    private val accountSubscriptions: AccountSubscriptions,
    private val downloads: DownloadManager,
    private val groups: GroupServices,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /**
     * The two ways to read a channel, as one collaborator: InnerTube (which carries upload
     * dates, shorts and playlists) and the yt-dlp uploads list it falls back to for a
     * channel reached by handle, whose URL carries no `UC…` id. They are alternatives for
     * the same job, so they travel together.
     */
    class ChannelReader(val innerTube: YouTubeChannel, val fallback: ChannelRepository)

    enum class Tab { VIDEOS, SHORTS, PLAYLISTS, SEARCH }

    /**
     * One tab's load state. Paging lives here rather than in three parallel fields: every
     * tab pages the same way, so the state that describes a tab describes its paging too.
     */
    data class TabState<T>(
        val loading: Boolean = false,
        val error: Boolean = false,
        val loaded: Boolean = false,
        val items: List<T> = emptyList(),
        /** Where the next page starts; null once the tab has no more to give. */
        val next: PageToken? = null,
        val loadingMore: Boolean = false,
    ) {
        val canLoadMore: Boolean get() = next != null && !loadingMore && !loading

        val foundSomething: Boolean get() = items.isNotEmpty() && !loading
    }

    data class UiState(
        val title: String,
        val tab: Tab = Tab.VIDEOS,
        val videos: TabState<MediaItem> = TabState(),
        val shorts: TabState<MediaItem> = TabState(),
        val playlists: TabState<Playlist> = TabState(),
        /** Results of searching within this channel, and the query they belong to. */
        val searchResults: TabState<MediaItem> = TabState(),
        val searchQuery: String = "",
        val subscribed: Boolean = false,
        /** Dewi's groups, so the picker can show which ones this channel is already in. */
        val groups: List<SourceGroup> = emptyList(),
        val downloadStates: Map<MediaItemId, DownloadState> = emptyMap(),
        val resolving: String? = null,
        val artworkUrl: HttpUrl? = null,
        val channelId: String? = null,
    )

    private data class Content(
        val title: String,
        val tab: Tab = Tab.VIDEOS,
        val videos: TabState<MediaItem> = TabState(),
        val shorts: TabState<MediaItem> = TabState(),
        val playlists: TabState<Playlist> = TabState(),
        val searchResults: TabState<MediaItem> = TabState(),
        val searchQuery: String = "",
        val resolving: String? = null,
        /**
         * The channel's `UC…` id once something has actually resolved it.
         *
         * A channel reached by handle — /@name — carries no id in its URL, so it could only be
         * compared to the account's subscriptions by URL string, and those never match. The
         * yt-dlp fallback resolves the real channel and its result has carried the id all
         * along; it was simply discarded.
         */
        val resolvedChannelId: String? = null,
    )

    private val channelId: String? = source.youTubeChannelId

    private val content = MutableStateFlow(Content(source.title))

    val uiState: StateFlow<UiState> = combine(
        content,
        accountSubscriptions.channels,
        downloads.observeDownloads(),
        groups.store.observeGroups(),
    ) { c, subs, downloadStates, groups ->
        UiState(
            title = c.title,
            tab = c.tab,
            videos = c.videos,
            shorts = c.shorts,
            playlists = c.playlists,
            searchResults = c.searchResults,
            searchQuery = c.searchQuery,
            // Compared by CHANNEL, not by URL. The account's subscriptions arrive keyed by their
            // canonical /channel/UC… URL while a channel opened from a video row or a search hit
            // carries whatever form that source used, so string equality reported "not subscribed"
            // for channels Dewi was plainly subscribed to.
            subscribed = subs.subscribedTo(c),
            groups = groups,
            downloadStates = downloadStates,
            resolving = c.resolving,
            artworkUrl = source.artworkUrl ?: subs.artworkFor(c),
            channelId = channelId ?: c.resolvedChannelId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UiState(source.title))

    /**
     * Logged, not just returned. "It offered me Subscribe to a channel I follow" has three
     * possible causes that look identical on screen — the id never resolved, the account list
     * is short, or the two ids genuinely differ — and only the values tell them apart. Logged
     * on change alone, since this recomputes on every download tick.
     */
    private fun List<MediaSource.VideoChannel>.subscribedTo(c: Content): Boolean {
        val id = channelId ?: c.resolvedChannelId
        val answer = containsChannel(source, id)
        val decision = "${source.title} id=${id ?: "unresolved"} in $size subs -> $answer"
        if (decision != lastSubscribedDecision) {
            lastSubscribedDecision = decision
            Diag.log("channel", "subscribed? $decision")
        }
        return answer
    }

    private var lastSubscribedDecision: String? = null

    private class TabLens<T>(
        val tab: Tab,
        val state: (Content) -> TabState<T>,
        val update: (Content, TabState<T>) -> Content,
    )

    private val videosTab = TabLens(Tab.VIDEOS, { it.videos }) { c, s -> c.copy(videos = s) }
    private val shortsTab = TabLens(Tab.SHORTS, { it.shorts }) { c, s -> c.copy(shorts = s) }
    private val playlistsTab = TabLens(Tab.PLAYLISTS, { it.playlists }) { c, s -> c.copy(playlists = s) }
    private val searchTab = TabLens(Tab.SEARCH, { it.searchResults }) { c, s -> c.copy(searchResults = s) }
    private val refreshable = listOf(videosTab, shortsTab, playlistsTab)

    private val finishedAtMs = mutableMapOf<Tab, Long>()

    private fun List<MediaSource.VideoChannel>.artworkFor(c: Content): HttpUrl? =
        findChannel(source, channelId ?: c.resolvedChannelId)?.artworkUrl

    /** Adds this channel to [group], or removes it. The picker is a checklist of these. */
    fun toggleGroup(group: SourceGroup) {
        viewModelScope.launch {
            val added = groups.store.toggleMember(group.id, source)
            Diag.log("group", "\"${source.title}\" ${if (added) "added to" else "removed from"} \"${group.name}\"")
        }
    }

    fun renameGroup(group: SourceGroup, name: String) {
        viewModelScope.launch {
            groups.store.rename(group.id, name)
            Diag.log("group", "renamed \"${group.name}\" to \"$name\"")
        }
    }

    fun deleteGroup(group: SourceGroup) {
        viewModelScope.launch {
            groups.store.delete(group.id)
            Diag.log("group", "deleted \"${group.name}\" (${group.members.size} members)")
        }
    }

    /** Creates a group and puts this channel in it — you name one when you have something for it. */
    fun createGroupWith(name: String) {
        viewModelScope.launch {
            val id = groups.store.create(name)
            groups.store.toggleMember(id, source)
            Diag.log("group", "created \"$name\" starting with \"${source.title}\"")
        }
    }

    init {
        Diag.log(
            "channel",
            "opened \"${source.title}\" ${source.channelUrl.value} " +
                if (channelId != null) "(UC id known: tabs load via InnerTube)" else "(handle only: videos via yt-dlp)",
        )
        loadVideos(refreshing = false)
    }

    fun refreshOnEntry() = refreshable.forEach { refreshIfStale(it.tab, "on entry") }

    private fun refreshIfStale(tab: Tab, moment: String) {
        val state = refreshable.firstOrNull { it.tab == tab }?.state?.invoke(content.value) ?: return
        val finishedAt = finishedAtMs[tab]
        val name = tab.name.lowercase()
        if (state.loading || finishedAt == null) {
            val why = if (state.loading) "a load is already running" else "no load has finished yet"
            Diag.log("channel", "\"${source.title}\" $name not reloaded $moment: $why")
            return
        }
        val ageSeconds = (now() - finishedAt) / MILLIS_PER_SECOND
        val why = when {
            state.error -> "the last load failed ${ageSeconds}s ago"
            ageSeconds * MILLIS_PER_SECOND >= TAB_STALE_AFTER_MS -> "the list is ${ageSeconds}s old"
            else -> null
        }
        if (why == null) {
            Diag.log("channel", "\"${source.title}\" $name reused: ${state.items.size} item(s), ${ageSeconds}s old")
            return
        }
        Diag.log(
            "channel",
            "\"${source.title}\" $name reloaded $moment: $why; keeping ${state.items.size} item(s) meanwhile"
        )
        loadTab(tab, refreshing = true)
    }

    private fun loadTab(tab: Tab, refreshing: Boolean) = when (tab) {
        Tab.VIDEOS -> loadVideos(refreshing)
        Tab.SHORTS -> loadShorts(refreshing)
        Tab.PLAYLISTS -> loadPlaylists(refreshing)
        Tab.SEARCH -> Unit
    }

    private fun Page<MediaItem>.listedHere(): Page<MediaItem> {
        val unnamed = items.count { it.author == null || it.sourceUrl == null }
        if (unnamed > 0) {
            Diag.log(
                "channel",
                "\"${source.title}\": $unnamed of ${items.size} item(s) listed without their channel, given this one"
            )
        }
        return map { it.listedOn(source) }
    }

    private fun logLoad(tab: String, route: String, started: TimeSource.Monotonic.ValueTimeMark, items: Int?) {
        val ms = started.elapsedNow().inWholeMilliseconds
        val outcome = items?.let { "$it item(s)" } ?: "FAILED"
        Diag.log("channel", "\"${source.title}\" $tab via $route: $outcome in ${ms}ms")
    }

    fun selectTab(tab: Tab) {
        content.update { it.copy(tab = tab) }
        // Nothing to load until there is a query to load it for.
        val lens = refreshable.firstOrNull { it.tab == tab } ?: return
        val loaded = lens.state(content.value).loaded
        if (loaded) refreshIfStale(tab, "on selecting it") else loadTab(tab, refreshing = false)
    }

    /**
     * Searches within this channel. Blank clears the results rather than searching for
     * nothing, so backspacing out of a query does not leave stale hits on screen.
     */
    fun search(query: String) {
        val trimmed = query.trim()
        content.update { it.copy(searchQuery = query) }
        val id = channelId ?: return
        if (trimmed.isEmpty()) {
            content.update { it.copy(searchResults = TabState()) }
            return
        }
        viewModelScope.launch {
            content.update { it.copy(searchResults = it.searchResults.copy(loading = true, error = false)) }
            val page = videoPage(reader.innerTube.search(id, trimmed))
            // Dropped if the query moved on while this was in flight.
            if (content.value.searchQuery.trim() != trimmed) return@launch
            content.update {
                it.copy(
                    searchResults = TabState(
                        loaded = true,
                        error = page == null,
                        items = page?.items.orEmpty(),
                        next = page?.next,
                    ),
                )
            }
        }
    }

    private fun loadVideos(refreshing: Boolean) =
        reload(videosTab, refreshing, if (channelId != null) "InnerTube" else "yt-dlp") {
            channelId?.let { id ->
                when (val r = reader.innerTube.videos(id)) {
                    is ChannelVideos.Success -> r.page.map { it.toMediaItem(source.id) }.listedHere()
                    is ChannelVideos.Failure -> null
                }
                // The yt-dlp fallback returns everything it found in one go, so it is a last
                // page by nature rather than by omission.
            } ?: fallbackVideos()?.let { Page.last(it) }
        }

    private fun <T> reload(lens: TabLens<T>, refreshing: Boolean, route: String, fetch: suspend () -> Page<T>?) {
        val name = lens.tab.name.lowercase()
        if (lens.state(content.value).loading) {
            Diag.log("channel", "\"${source.title}\" $name already loading; not starting another")
            return
        }
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            content.update { lens.update(it, lens.state(it).copy(loading = true, error = false)) }
            val page = fetch()
            logLoad(name, route, started, page?.items?.size)
            finishedAtMs[lens.tab] = now()
            if (page == null && refreshing) {
                Diag.log("channel", "\"${source.title}\" $name refresh failed; the earlier list stays")
                content.update {
                    lens.update(it, lens.state(it).copy(loading = false, error = lens.state(it).items.isEmpty()))
                }
                return@launch
            }
            content.update {
                lens.update(
                    it,
                    TabState(loaded = true, error = page == null, items = page?.items.orEmpty(), next = page?.next),
                )
            }
        }
    }

    /** yt-dlp uploads for a channel we can't address by `UC…` id. */
    private suspend fun fallbackVideos(): List<MediaItem>? =
        when (val r = reader.fallback.fetchChannelVideos(source.channelUrl)) {
            is ChannelVideosResult.Success -> {
                // Keep the id it resolved: this is the only way a handle-only channel ever
                // learns its own UC id, and without it the subscribe button cannot tell whether
                // you follow this channel.
                content.update { it.copy(resolvedChannelId = r.channelId) }
                Diag.log("channel", "resolved ${source.title} to ${r.channelId}")
                Page.last(r.videos).listedHere().items
            }

            is ChannelVideosResult.Failure -> null
        }

    private fun loadShorts(refreshing: Boolean) {
        val id = channelId ?: run {
            content.update { it.copy(shorts = TabState(loaded = true)) }
            return
        }
        reload(shortsTab, refreshing, "InnerTube") {
            when (val r = reader.innerTube.shorts(id)) {
                is ChannelVideos.Success -> r.page.map { it.toMediaItem(source.id) }.listedHere()
                is ChannelVideos.Failure -> null
            }
        }
    }

    private fun loadPlaylists(refreshing: Boolean) {
        val id = channelId ?: run {
            content.update { it.copy(playlists = TabState(loaded = true)) }
            return
        }
        reload(playlistsTab, refreshing, "InnerTube") {
            when (val r = reader.innerTube.playlists(id)) {
                is ChannelPlaylists.Success -> r.page
                is ChannelPlaylists.Failure -> null
            }
        }
    }

    /**
     * Loads the next page of whichever tab is showing. Self-guarding like every other
     * paged screen: no overlapping requests, a no-op once the tab is exhausted, and a
     * failed page keeps its token so scrolling retries instead of ending the tab.
     */
    fun loadMore() {
        val id = channelId ?: return
        when (content.value.tab) {
            Tab.VIDEOS -> pageMore(videosTab) { after -> videoPage(reader.innerTube.videos(id, after)) }
            Tab.SHORTS -> pageMore(shortsTab) { after -> videoPage(reader.innerTube.shorts(id, after)) }
            Tab.PLAYLISTS -> pageMore(playlistsTab) { after ->
                (reader.innerTube.playlists(id, after) as? ChannelPlaylists.Success)?.page
            }
            Tab.SEARCH -> pageMore(searchTab) { after ->
                videoPage(reader.innerTube.search(id, content.value.searchQuery.trim(), after))
            }
        }
    }

    /** A tab result as domain items, or null when it failed. */
    private fun videoPage(result: ChannelVideos): Page<MediaItem>? =
        result.toMediaItemPage(source.id)?.listedHere()

    /**
     * The paging step, once, for any tab. The three tabs differ only in which state they
     * read and which call they make, so those are the parameters and nothing else is
     * duplicated.
     */
    private fun <T> pageMore(lens: TabLens<T>, fetch: suspend (PageToken) -> Page<T>?) {
        val state = lens.state
        val update = lens.update
        val current = state(content.value)
        val after = current.next ?: return
        if (current.loadingMore || current.loading) return
        content.update { update(it, state(it).copy(loadingMore = true)) }
        viewModelScope.launch {
            val started = TimeSource.Monotonic.markNow()
            val fetched = fetch(after)
            logLoad("${content.value.tab.name.lowercase()} next page", "InnerTube", started, fetched?.items?.size)
            content.update { c ->
                val existing = state(c)
                val merged = if (fetched == null) {
                    // Keep the token: one flaky request must not end the tab.
                    existing.copy(loadingMore = false)
                } else {
                    existing.copy(
                        items = existing.items + fetched.items.filterNot { it in existing.items },
                        next = fetched.next,
                        loadingMore = false,
                    )
                }
                update(c, merged)
            }
        }
    }

    fun play(video: MediaItem) {
        val watchUrl = video.mediaUrl ?: return
        viewModelScope.launch {
            content.update { it.copy(resolving = watchUrl.value) }
            queue.playNow(PlayableItem(video, PlayHandle.Video(watchUrl)))
            content.update { it.copy(resolving = null) }
        }
    }

    fun toggleSubscribed() {
        val target = !uiState.value.subscribed
        viewModelScope.launch { accountSubscriptions.setSubscribed(source, target) }
    }

    fun deleteDownload(video: MediaItem) {
        viewModelScope.launch { downloads.delete(video.id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L
        private const val TAB_STALE_AFTER_MS = 5 * 60_000L
        private const val MILLIS_PER_SECOND = 1_000L

        fun factory(container: AppContainer, source: MediaSource.VideoChannel): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChannelViewModel(
                        source = source,
                        reader = ChannelReader(container.youTubeChannel, container.channelRepository),
                        queue = container.playbackQueue,
                        accountSubscriptions = container.accountSubscriptions,
                        downloads = container.downloadManager,
                        groups = GroupServices(container.sourceGroupStore, container.groupFeed),
                    )
                }
            }
    }
}
