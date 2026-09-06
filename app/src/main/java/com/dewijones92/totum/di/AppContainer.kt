package com.dewijones92.totum.di

import android.content.Context
import android.os.StatFs
import androidx.core.os.LocaleListCompat
import com.dewijones92.totum.BuildConfig
import com.dewijones92.totum.account.SharedPrefsTokenStore
import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.backup.asBackupSettings
import com.dewijones92.totum.busy.BusyInterceptor
import com.dewijones92.totum.busy.BusyYtDlpEngine
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.channel.ChannelRepository
import com.dewijones92.totum.data.channel.DefaultChannelRepository
import com.dewijones92.totum.data.content.ContentRefresher
import com.dewijones92.totum.data.content.PodcastSubscriptionItemsSource
import com.dewijones92.totum.data.content.SeenItemsTracker
import com.dewijones92.totum.data.download.DefaultDownloadManager
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.data.download.EngineDownloadStrategy
import com.dewijones92.totum.data.download.FallbackDownloadStrategy
import com.dewijones92.totum.data.download.HttpDownloadStrategy
import com.dewijones92.totum.data.download.RoutedDownloadStrategy
import com.dewijones92.totum.data.feed.FeedCache
import com.dewijones92.totum.data.group.ChannelSourceItems
import com.dewijones92.totum.data.group.GroupFeed
import com.dewijones92.totum.data.group.PodcastSourceItems
import com.dewijones92.totum.data.group.RoutedSourceItems
import com.dewijones92.totum.data.group.SourceGroupStore
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.data.importexport.OpmlExporter
import com.dewijones92.totum.data.importexport.SubscriptionImportParser
import com.dewijones92.totum.data.net.OkHttpTextFetcher
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.data.podcast.DefaultPodcastRepository
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.queue.QueueStore
import com.dewijones92.totum.data.search.FallbackSearchSource
import com.dewijones92.totum.data.search.InnerTubeMusicSearchSource
import com.dewijones92.totum.data.search.InnerTubeVideoSearchSource
import com.dewijones92.totum.data.search.ItunesPodcastSearchSource
import com.dewijones92.totum.data.search.SearchHistoryStore
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.data.search.TorrentSearchSource
import com.dewijones92.totum.data.search.YtDlpVideoSearchSource
import com.dewijones92.totum.data.source.DefaultSourceLocator
import com.dewijones92.totum.data.source.SourceLocator
import com.dewijones92.totum.data.sponsorblock.SkipSegmentSource
import com.dewijones92.totum.data.sponsorblock.SponsorBlockSegmentSource
import com.dewijones92.totum.data.torrent.HomeTorrentServer
import com.dewijones92.totum.data.torrent.HttpHomeTorrentServer
import com.dewijones92.totum.data.torrent.hasAudioOnlyFetch
import com.dewijones92.totum.database.RoomAccountProgressOutbox
import com.dewijones92.totum.database.RoomDownloadStore
import com.dewijones92.totum.database.RoomFeedCache
import com.dewijones92.totum.database.RoomLocalPlaylistStore
import com.dewijones92.totum.database.RoomPlayHistoryStore
import com.dewijones92.totum.database.RoomPlaybackProgressStore
import com.dewijones92.totum.database.RoomQueueStore
import com.dewijones92.totum.database.RoomSourceGroupStore
import com.dewijones92.totum.database.RoomSubscriptionStore
import com.dewijones92.totum.database.TotumDatabase
import com.dewijones92.totum.diagnostics.ActivitySnapshotter
import com.dewijones92.totum.diagnostics.CrashReporter
import com.dewijones92.totum.diagnostics.DiagnosticSnapshot
import com.dewijones92.totum.diagnostics.DiagnosticsUploader
import com.dewijones92.totum.diagnostics.installAndroidLogSink
import com.dewijones92.totum.domain.AccountProgressOutbox
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.LocalCopy
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.accountAwarePlayState
import com.dewijones92.totum.domain.deservesAnotherRoute
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.importexport.SubscriptionImporter
import com.dewijones92.totum.innertube.actions.HttpYouTubeActions
import com.dewijones92.totum.innertube.actions.YouTubeActions
import com.dewijones92.totum.innertube.auth.AccessTokenResult
import com.dewijones92.totum.innertube.auth.HttpYouTubeAuth
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.channel.HttpYouTubeChannel
import com.dewijones92.totum.innertube.channel.YouTubeChannel
import com.dewijones92.totum.innertube.comments.HttpYouTubeComments
import com.dewijones92.totum.innertube.comments.YouTubeComments
import com.dewijones92.totum.innertube.feeds.HttpYouTubeFeeds
import com.dewijones92.totum.innertube.feeds.YouTubeFeeds
import com.dewijones92.totum.innertube.history.HttpYouTubeWatchHistory
import com.dewijones92.totum.innertube.history.YouTubeWatchHistory
import com.dewijones92.totum.innertube.music.HttpYouTubeMusicSearch
import com.dewijones92.totum.innertube.player.HttpSignatureTimestampSource
import com.dewijones92.totum.innertube.player.NSolver
import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import com.dewijones92.totum.innertube.player.withSolvedN
import com.dewijones92.totum.innertube.playlists.HttpYouTubePlaylists
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import com.dewijones92.totum.innertube.related.HttpYouTubeRelated
import com.dewijones92.totum.innertube.related.RelatedResult
import com.dewijones92.totum.innertube.related.YouTubeRelated
import com.dewijones92.totum.innertube.search.HttpYouTubeSearch
import com.dewijones92.totum.innertube.subscriptions.HttpYouTubeSubscriptions
import com.dewijones92.totum.notifications.DataSaverNotifier
import com.dewijones92.totum.notifications.DownloadNotifier
import com.dewijones92.totum.notifications.SharedPrefsSeenItemsTracker
import com.dewijones92.totum.notifications.YouTubeSubscriptionItemsSource
import com.dewijones92.totum.playback.AutoAdvancer
import com.dewijones92.totum.playback.Media3PlaybackController
import com.dewijones92.totum.playback.MeteredAudioSwitch
import com.dewijones92.totum.playback.NextUpPrefetcher
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackProgressStore
import com.dewijones92.totum.playback.SharedPrefsPlaybackSpeedStore
import com.dewijones92.totum.playback.SharedPrefsVolumeBoostStore
import com.dewijones92.totum.playback.SleepTimer
import com.dewijones92.totum.playback.StallWatchdog
import com.dewijones92.totum.playback.StreamRecovery
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.QueueAutoDownloader
import com.dewijones92.totum.search.SharedPrefsSearchHistoryStore
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.settings.NetworkStatus
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.settings.SharedPrefsAppPreferences
import com.dewijones92.totum.settings.listeningIn
import com.dewijones92.totum.ui.common.toMediaItem
import com.dewijones92.totum.video.AccountResumePositions
import com.dewijones92.totum.video.AccountSubscriptions
import com.dewijones92.totum.video.InnerTubePlayerStreams
import com.dewijones92.totum.video.PlatformVideoCodecSupport
import com.dewijones92.totum.video.PlayerBackedDownloadStrategy
import com.dewijones92.totum.video.ProgressOutboxDrain
import com.dewijones92.totum.video.StreamChoices
import com.dewijones92.totum.video.VideoPlaybackLauncher
import com.dewijones92.totum.video.VideoResolver
import com.dewijones92.totum.video.WatchHistorySync
import com.dewijones92.totum.ytdlp.InteractiveFirstEngine
import com.dewijones92.totum.ytdlp.YtDlpEngine
import com.dewijones92.totum.ytdlp.chaquopy.ChaquopyYtDlpEngine
import com.dewijones92.totum.ytdlp.chaquopy.YtDlpUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** The app's dependency graph. Manual DI: construction is code, errors are compile-time. */
interface AppContainer {
    /**
     * For work that must outlive the screen that started it.
     *
     * Starting playback is the case that matters: a tap kicks off an extraction that takes
     * a second or two, and a composition-bound scope dies the moment the user changes tabs,
     * cancelling it silently. A real report caught exactly that — extraction completed, the
     * user switched tab 1.7s later, and nothing ever played.
     */
    val applicationScope: CoroutineScope

    /**
     * Whether we are LISTENING right now, resolving [PlaybackMode.AUTO] against the network.
     *
     * On the interface because the UI needs the SAME answer playback uses. It did not have it: rows
     * hand-rolled `mode == AUDIO`, which is false on AUTO however metered the connection — and AUTO is
     * the default. So on mobile data the app played audio while every row offered "Listen only" and
     * never offered the picture back. See [listeningIn].
     */
    val listeningNow: Boolean

    val podcastRepository: PodcastRepository
    val channelRepository: ChannelRepository
    val ytDlpEngine: YtDlpEngine
    val playbackController: PlaybackController
    val podcastSearchSource: SearchSource
    val videoSearchSource: SearchSource

    /** YouTube Music songs — the same seam, a different catalogue. */
    val musicSearchSource: SearchSource

    /**
     * Torrents from the home server, or null when it has not been configured.
     *
     * Null rather than a source that always fails, so the UI can leave the whole thing out
     * instead of showing an error for a feature nobody has set up.
     */
    val torrentSearchSource: SearchSource?

    /** Adds a torrent to the home server and turns it into queue items; null when unconfigured. */
    val homeTorrentServer: HomeTorrentServer?

    /** Whether automatic downloads may run on the current connection — see the implementation. */
    fun autoDownloadAllowedNow(): Boolean

    /** Whether there is no usable network — what makes a non-downloaded queue row unplayable. */
    fun isOffline(): Boolean

    /** Recent search queries, offered again in the search screen's idle state. */
    val searchHistoryStore: SearchHistoryStore
    val skipSegmentSource: SkipSegmentSource
    val downloadManager: DownloadManager

    /** Full backup / restore of everything that is not re-downloadable. */
    val backupService: BackupService

    /** Free space where downloads live; null when it cannot be read. */
    fun freeDownloadSpaceBytes(): Long?
    val videoResolver: VideoResolver
    val videoPlaybackLauncher: VideoPlaybackLauncher

    /** Named groups of sources, read as one merged feed. */
    /** Last-known feed contents, so the Videos tab opens with something on it. */
    val feedCache: FeedCache

    val sourceGroupStore: SourceGroupStore

    /** Reads a group's members as one newest-first feed, across both pillars. */
    val groupFeed: GroupFeed

    /** Sleep timer that pauses playback after a chosen delay. */
    val sleepTimer: SleepTimer

    /** The unified up-next queue (what plays after the current item), both pillars. */
    val playbackQueue: PlaybackQueue

    /** Persists the queue so it survives a restart. */
    val queueStore: QueueStore

    /**
     * Starts fetching the audio of everything in the queue (honouring the
     * auto-download settings), so the queue is listenable offline.
     */
    fun startQueueAutoDownload()

    /** Starts reporting download progress, completions and failures in the shade. */
    fun startDownloadNotifications()

    /**
     * Installs the crash handler and sends any reports left by a previous run. Called
     * first at startup so a failure during the rest of it is still reported.
     */
    fun installCrashReporting()

    /**
     * Captures the app's current state and event trail and sends it, with no crash
     * involved — for "this behaved wrongly", which is how most bugs actually present.
     */
    fun sendDiagnostics(note: String)

    /** User-curated local playlists, mixing podcasts and videos. */
    val localPlaylistStore: LocalPlaylistStore

    /** Recently-played items across both pillars. */
    val playHistoryStore: PlayHistoryStore

    /** Finds the source (channel / feed) a media row came from, for "go to channel". */
    val sourceLocator: SourceLocator

    /**
     * Resume positions and played/unplayed state. Exposed so every list can label its
     * rows from one source, rather than each screen carrying its own copy.
     */
    val playbackProgressStore: PlaybackProgressStore

    /**
     * What every ROW shows: this device's play state merged with the account's watched position,
     * by the same rule resuming uses. A video half-watched on the website shows half-watched here.
     */
    val rowPlayStates: Flow<Map<MediaItemId, PlayState>>

    /** User settings (per-network default quality, …). */
    val appPreferences: AppPreferences

    /** The signed-in account's subscribed channels, read live (no local copy). */
    val accountSubscriptions: AccountSubscriptions

    /** The signed-in YouTube account seam (device-code login, token upkeep). */
    val youTubeAccount: YouTubeAccount

    /** The signed-in account's video feeds (home, subs, watch later, history). */
    val youTubeFeeds: YouTubeFeeds

    /** A video's comments (public; no sign-in needed). */
    val youTubeComments: YouTubeComments

    /** A video's related / "up next" list (public; no sign-in needed). */
    val youTubeRelated: YouTubeRelated

    /** A channel's public tabs — Videos / Shorts / Playlists (no sign-in needed). */
    val youTubeChannel: YouTubeChannel

    /** Authenticated write actions (like, subscribe, comment). */
    val youTubeActions: YouTubeActions

    val youTubePlaylists: YouTubePlaylists

    /** Seen-state for the in-app bell (new since the user last opened the list). */
    val bellSeenTracker: SeenItemsTracker

    /** Finds new content across both pillars for the background notifications. */
    val contentRefresher: ContentRefresher

    /** Imports subscriptions from other apps (OPML / NewPipe / Takeout) and exports them as OPML. */
    val subscriptionImporter: SubscriptionImporter

    /**
     * Kick off background upkeep on app start (currently: fetch the latest
     * yt-dlp so YouTube-breaking changes get fixed without an app update).
     * Safe to call on every launch; never blocks and never touches Python.
     */
    fun refreshExtractorEngine()

    /**
     * Start mirroring video watch-progress to YouTube's servers as playback
     * advances (History + cross-device resume). No-ops while signed out.
     */
    fun startWatchHistorySync()

    /**
     * Load the signed-in account's subscribed channels into [accountSubscriptions]
     * (read live, never copied). Runs in the background on launch; no-ops while
     * signed out.
     */
    fun refreshSubscriptions()
}

// The count is the app's whole integration surface — every start*/install* entry point the
// Application calls, plus the few private wire-ups they need. Splitting it would scatter the
// one place the graph is built, which is the point of the class.
@Suppress("TooManyFunctions")
class DefaultAppContainer(private val context: Context) : AppContainer {

    /**
     * The client for long transfers, WITHOUT the busy interceptor.
     *
     * A podcast download runs for minutes on this stack, and a global indicator lit for the
     * whole of it would say nothing — the bar exists to tell working from idle. Downloads
     * have their own progress row and notification instead.
     */
    private val transferClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Everything interactive. One interceptor here is what makes the global loading bar
     * work for InnerTube, podcast feeds, SponsorBlock and the iTunes directory at once,
     * with no screen having to remember to report itself.
     */
    private val httpClient: OkHttpClient = transferClient.newBuilder()
        .addInterceptor(BusyInterceptor())
        .build()

    private val database: TotumDatabase = TotumDatabase.build(context)

    override val podcastRepository: PodcastRepository by lazy {
        DefaultPodcastRepository(
            fetcher = textFetcher,
            store = RoomSubscriptionStore(database.podcastDao(), RoomSubscriptionStore.SourceType.PODCAST),
        )
    }

    override val channelRepository: ChannelRepository by lazy {
        DefaultChannelRepository(engine = ytDlpEngine)
    }

    // Shared between the engine (activates a cached wheel) and the updater
    // (downloads into it), so a downloaded yt-dlp takes effect next start.
    private val ytDlpUpdateDir = File(context.filesDir, "ytdlp-update")

    override val ytDlpEngine: YtDlpEngine by lazy {
        // Wrapped so extraction reports itself to the global loading bar. This is the slowest
        // thing the app does — an embedded Python interpreter plus a JS runtime, ~8s of
        // startup on first use — so it is the work most worth telling the user about.
        BusyYtDlpEngine(InteractiveFirstEngine(ChaquopyYtDlpEngine(context, updateCacheDir = ytDlpUpdateDir)))
    }

    private val ytDlpUpdater by lazy { YtDlpUpdater(httpClient, ytDlpUpdateDir) }

    override fun refreshExtractorEngine() {
        applicationScope.launch { ytDlpUpdater.ensureLatest() }
    }

    override val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override val playbackProgressStore: PlaybackProgressStore by lazy {
        RoomPlaybackProgressStore(database.playbackProgressDao())
    }

    /**
     * The local store, plus YouTube's opinion about where a video was left.
     *
     * A decorator rather than a second lookup at the call site: `Media3PlaybackController` asks one
     * thing where an item resumes, and that stays true. Podcasts fall straight through — YouTube has
     * no position for them, so the rule returns the local answer untouched.
     */
    /** The one reading of "auto-play next", shared by end-of-item advance, the stall watchdog and recovery. */
    private val autoPlayNextEnabled: () -> Boolean = { appPreferences.settings.value.autoPlayNext }

    private val accountResumePositions: AccountResumePositions by lazy {
        AccountResumePositions(
            local = playbackProgressStore::resumePositionMs,
            history = youTubeWatchHistory,
            scope = applicationScope,
            // Known before asking, so an offline play never even starts the read that used to hang it.
            offline = ::isOffline,
        )
    }

    private val resumePositions: PlaybackProgressStore by lazy {
        object : PlaybackProgressStore by playbackProgressStore {
            override suspend fun resumePositionMs(itemId: MediaItemId): Long? =
                accountResumePositions.resumePositionMs(itemId)
        }
    }

    override val rowPlayStates: Flow<Map<MediaItemId, PlayState>> by lazy {
        channelFlow {
            // Refreshed for as long as anyone is looking, on the account cache's own staleness
            // window — a shorter loop would only ever hit the cache.
            launch {
                while (true) {
                    accountResumePositions.refresh()
                    delay(ACCOUNT_WATCHED_REFRESH_MS)
                }
            }
            combine(playbackProgressStore.observeStates(), accountResumePositions.watched) { local, remote ->
                // One rule, the same one resume uses. Dropping Unplayed keeps the map to items with news.
                (local.keys + remote.keys.map(::MediaItemId)).associateWith { id ->
                    val account = remote[id.value]
                    accountAwarePlayState(local[id], account?.positionMs, account?.durationMs)
                }.filterValues { it != PlayState.Unplayed }
            }.collect { send(it) }
        }
    }

    override val playbackController: PlaybackController by lazy {
        Media3PlaybackController(
            context,
            applicationScope,
            resumePositions,
            SharedPrefsPlaybackSpeedStore(context),
            SharedPrefsVolumeBoostStore(context),
            // Podcasts play straight through the controller (their enclosure URL is
            // stable), so record their history here; videos are recorded at the
            // launcher, which knows the stable watch URL.
            onPlay = { item, kind ->
                if (kind == MediaKind.PODCAST) {
                    applicationScope.launch {
                        playHistoryStore.record(PlayableItem(item, PlayHandle.Podcast()))
                    }
                }
            },
        )
    }

    private val textFetcher by lazy { OkHttpTextFetcher(httpClient) }

    override val podcastSearchSource: SearchSource by lazy {
        ItunesPodcastSearchSource(textFetcher)
    }

    /**
     * Configured only when a home server address is set, which is what makes the feature opt-in.
     *
     * The client carries no auth of its own: every request goes through the same OkHttp stack,
     * whose cookie jar holds the oauth2-proxy session obtained at sign-in. Authentication is
     * therefore a property of the HTTP client rather than something this class knows about.
     */
    override val homeTorrentServer: HomeTorrentServer? by lazy {
        val settings = appPreferences.settings.value
        val base = settings.homeServerBase.takeIf { it.isNotBlank() } ?: return@lazy null
        HttpHomeTorrentServer(
            // Its own read timeout, because a torrent search is nothing like the app's other
            // requests: it fans out to every indexer and waits for the slowest. Measured
            // 2026-08-02, one query answered in 10.7s and another was still going at 60s — both
            // well past the 20s that suits a feed fetch, and a cut-off would have looked to the
            // person searching exactly like "no results". Shares the connection pool, so this
            // costs nothing but the setting.
            client = httpClient.newBuilder()
                .readTimeout(TORRENT_SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(TORRENT_SEARCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build(),
            base = "https://totum.$base",
            // BOTH read per call, so signing in takes effect immediately rather than after a
            // restart. The key used to be captured here by value, which meant a fresh install
            // built this with no key and never picked up the one sign-in delivered.
            prowlarrApiKey = { appPreferences.settings.value.prowlarrApiKey },
            token = { appPreferences.settings.value.homeServerToken },
        )
    }

    override val torrentSearchSource: SearchSource? by lazy {
        homeTorrentServer?.let(::TorrentSearchSource)
    }

    override val videoSearchSource: SearchSource by lazy {
        // InnerTube first (it carries upload dates and needs no Python); the
        // engine's ytsearch stays as the fallback if YouTube's shape changes.
        FallbackSearchSource(
            // Attributed to the account when signed in, so a search feeds the recommendations
            // rather than crediting nobody; it falls back to the anonymous search on its own.
            primary = InnerTubeVideoSearchSource(HttpYouTubeSearch(innerTubeClient, ::searchToken)),
            fallback = YtDlpVideoSearchSource(ytDlpEngine),
        )
    }

    /**
     * No fallback, deliberately. yt-dlp has no music catalogue to fall back TO, and a section
     * that says it failed is better than one quietly filled with ordinary video results.
     */
    override val musicSearchSource: SearchSource by lazy {
        InnerTubeMusicSearchSource(HttpYouTubeMusicSearch(innerTubeClient))
    }

    /**
     * The account token for any request that can carry one, or null to leave it anonymous.
     *
     * Null on any problem rather than propagating one: an expired refresh must cost a request
     * nothing when there is an anonymous path right there.
     */
    private suspend fun searchToken() =
        (youTubeAccount.accessToken() as? AccessTokenResult.Available)?.token

    override val searchHistoryStore: SearchHistoryStore by lazy {
        SharedPrefsSearchHistoryStore(context)
    }

    override val skipSegmentSource: SkipSegmentSource by lazy {
        SponsorBlockSegmentSource(textFetcher) { appPreferences.settings.value.skipCategories }
    }

    override fun freeDownloadSpaceBytes(): Long? =
        runCatching { StatFs(downloadDir.path).availableBytes }.getOrNull()

    private val downloadDir = File(context.filesDir, "downloads")

    override val backupService: BackupService by lazy {
        BackupService(
            subscriptions = RoomSubscriptionStore(database.podcastDao(), RoomSubscriptionStore.SourceType.PODCAST),
            playlists = localPlaylistStore,
            queueStore = queueStore,
            progress = playbackProgressStore,
            settings = appPreferences.asBackupSettings(),
            appVersion = BuildConfig.VERSION_NAME,
        )
    }

    override val downloadManager: DownloadManager by lazy {
        DefaultDownloadManager(
            downloadDir = downloadDir,
            store = RoomDownloadStore(database.downloadDao()),
            // Videos resolve+merge through the engine (bundled ffmpeg) and drop
            // SponsorBlock segments; podcast enclosures are a plain HTTP fetch.
            strategy = RoutedDownloadStrategy(
                // yt-dlp first, because it handles everything and cuts SponsorBlock out of the
                // file. Its one blind spot is anything YouTube only serves to an ACCOUNT —
                // members-only uploads, which it is refused and the app is not — so a permanent
                // refusal falls back to the app's own signed-in resolution.
                video = FallbackDownloadStrategy(
                    primary = EngineDownloadStrategy(
                        engine = ytDlpEngine,
                        sponsorBlockCategories = appPreferences.settings.value.skipCategories.mapTo(
                            mutableSetOf()
                        ) { it.id },
                        // The SAME preference playback resolves with. Without it, choosing German and
                        // downloading gave the English original -- and a downloaded file is precisely
                        // the one you cannot re-pick a track for.
                        preferredAudioLanguages = streamChoices::preferredAudioLanguages,
                    ),
                    secondary = PlayerBackedDownloadStrategy(
                        // The SAME resolution playback uses, so anything watchable is fetchable.
                        resolveAudioUrl = { item ->
                            (item.handle as? PlayHandle.Video)?.let { handle ->
                                videoResolver.resolve(handle.watchUrl, item.item.sourceId, asked = "download")
                                    ?.audioOnlyUrl
                            }
                        },
                        // NOT resumable: this route re-resolves the URL on every attempt and can be
                        // handed a different audio format each time, so continuing a part-fetched
                        // file would splice two formats into one that never plays.
                        http = HttpDownloadStrategy(transferClient, resumable = false),
                    ),
                    // The named domain rule, not a lambda spelled out here. This used to read
                    // `{ it.isPermanent }`, which withheld the second route from every 403 — and on
                    // 2026-08-18, when YouTube stopped serving yt-dlp's URLs at all, that meant a
                    // working route sat unreachable while nothing in the app could be downloaded.
                    shouldFallBack = { it.deservesAnotherRoute },
                ),
                podcast = HttpDownloadStrategy(transferClient),
            ),
            scope = applicationScope,
        )
    }

    override val feedCache: FeedCache by lazy { RoomFeedCache(database.cachedFeedDao()) }

    override val sourceGroupStore: SourceGroupStore by lazy {
        RoomSourceGroupStore(database.sourceGroupDao())
    }

    override val videoResolver: VideoResolver by lazy {
        VideoResolver(
            ytDlpEngine,
            skipSegmentSource,
            PlatformVideoCodecSupport(),
            // The InnerTube player source. Its PLAIN URLs are useless — measured
            // 2026-07-31, a ranged GET of an ANDROID-client stream serves the first megabyte
            // and then 403s at every offset beyond it, whatever the range size or User-Agent.
            // Everything past that is behind SABR, which is why this is wired as the SABR
            // path's source and NOT as a fallback for extraction.
            playerStreams = InnerTubePlayerStreams(
                innerTubeClient,
                accountPlayer,
                // Only the anonymous response needs solving here; the account path already
                // solved its own, and doing it twice would transform an answer into nonsense.
                solveN = { streaming ->
                    val playerUrl = runCatching { signatureTimestamps.playerScriptUrl() }.getOrNull()
                    if (playerUrl != null) {
                        streaming.withSolvedN(nSolver, playerUrl)
                    } else {
                        // FAIL CLOSED. Returning the streams untouched here looked harmless and
                        // was the worst option: their `n` is unsolved, so they 403 the moment
                        // playback opens them — a video that resolved in 112ms and then died,
                        // rather than one that took 14s and worked. Seen on the emulator
                        // 2026-08-02. An empty solver drops every format carrying an `n`, which
                        // sends the caller to extraction, and anything with no `n` survives.
                        Diag.warn("resolve", "no player script — dropping any stream that needs its n solved")
                        streaming.withSolvedN({ _, _ -> emptyMap() }, "")
                    }
                },
                // Signed in, ask the account FIRST. Its client is a TV one, which is what SmartTube is
                // — and SmartTube plays these videos in full on the same broadband where this app
                // cannot. The path existed and was unreachable: it only fired when the anonymous call
                // was refused, and the anonymous call succeeds and is then stripped of its video URLs by
                // YouTube's SABR-only experiment. A useless success is not a failure to a gate written
                // for failures.
                preferAccount = { accountSubscriptions.signedIn.value },
            ),
            sabrEnabled = { appPreferences.settings.value.sabrPlayback },
            resumePositionMs = playbackProgressStore::resumePositionMs,
            // The phone's own languages, best first. YouTube offers automatic dubs as ordinary
            // formats, so without this the app plays whichever the extractor happened to list
            // first — report 0.1.373 watched an English talk in German.
            preferredAudioLanguages = streamChoices::preferredAudioLanguages,
        )
    }

    /**
     * ONE instance, shared by the resolver and the launcher. Two would be the bug it exists to
     * fix: the audio half is applied while resolving and the quality half after, and they have to
     * be talking about the same choice.
     */
    private val streamChoices by lazy { StreamChoices(::deviceLanguages) }

    override val sleepTimer: SleepTimer by lazy {
        SleepTimer(playbackController, applicationScope)
    }

    override val queueStore: QueueStore by lazy { RoomQueueStore(database.queueDao()) }

    private val crashReporter by lazy { CrashReporter(context, stateProviders = ::diagnosticState) }

    override fun startDownloadNotifications() {
        DownloadNotifier(context, downloadManager, applicationScope).start()
    }

    /**
     * Mirrors a deliberate queue-add to YouTube's Watch Later.
     *
     * Dewi's ask: queueing something here should tell YouTube he likes it, so the algorithm
     * learns from what he lines up rather than only from what he finishes. Watch Later is the
     * right shelf for it — it is literally "I intend to watch this", which is what queueing
     * means — and it is a signal YouTube's own clients send.
     *
     * Only YouTube videos, and only while signed in. A podcast has no YouTube identity, and
     * signed out there is no account to teach. Both are silent no-ops rather than warnings,
     * because neither is a fault.
     */
    private suspend fun saveToWatchLater(queued: PlayableItem) {
        val handle = queued.handle as? PlayHandle.Video ?: return
        if (!accountSubscriptions.signedIn.value) {
            Diag.log("yt-signal", "not saving \"${queued.item.title}\" to Watch Later: signed out")
            return
        }
        val videoId = queued.item.id.value
        val result = youTubeActions.setSavedToWatchLater(videoId, saved = true)
        // Logged either way: this is the proof that queueing reached the account, and a silent
        // failure here would look identical to the feature not existing.
        Diag.log("yt-signal", "watch-later += $videoId -> $result")
    }

    /**
     * Plays the top related video when the queue has run out — the end-of-queue fallback.
     *
     * App-side rather than through the player's view model, which is where it used to live:
     * the queue running dry has nothing to do with whether a screen is on, and reaching into
     * a view model would have put the UI's lifecycle back in the path this change exists to
     * remove.
     */
    private suspend fun playRelatedNext() {
        val playing = playbackQueue.nowPlaying.value ?: return
        val related = youTubeRelated.relatedTo(playing.item.id.value)
        if (related !is RelatedResult.Success) {
            Diag.warn("advance", "no related videos to fall back on")
            return
        }
        val next = related.videos
            .firstOrNull { it.videoId != playing.item.id.value }
            ?.toMediaItem(SourceId("ytrelated"))
            ?.toPlayableOrNull()
        if (next == null) {
            Diag.warn("advance", "related list had nothing playable")
            return
        }
        Diag.log("advance", "queue empty — playing related \"${next.item.title}\"")
        playbackQueue.playNow(next)
    }

    override fun installCrashReporting() {
        installAndroidLogSink()
        crashReporter.install()
        // Turns the event trail into a timeline: transitions alone never show a download
        // stuck at 40%, which is exactly when it is the problem.
        ActivitySnapshotter(playbackController, downloadManager, playbackQueue, applicationScope).start()
        // A signed streaming URL expires in hours, so anything paused overnight comes back
        // to nothing but 403s. Re-resolve and carry on rather than retrying a dead address.
        // Auto-advance is app-scoped for the same reason the recovery is: it must keep
        // working with the screen off. It used to be a composable effect fed by
        // collectAsStateWithLifecycle, which stops collecting when the activity stops — so a
        // phone in a pocket never advanced (proven: a 7-minute gap between an item ending
        // and the decision being reached).
        AutoAdvancer(
            events = playbackController.events,
            advance = { playbackQueue.playNextInQueue() },
            whenQueueEmpty = ::playRelatedNext,
            isEnabled = autoPlayNextEnabled,
            scope = applicationScope,
        ).start()
        // The advancer only ever hears about a clean end. An item that stops dead at its own
        // end without reporting one leaves the queue silently stopped — see StallWatchdog.
        // Protects mobile data when the phone walks off Wi-Fi mid-video: 15.2 MB/min against 2.1
        // for the audio alone, measured. Holds before acting, so a lift or a tunnel is a no-op.
        MeteredAudioSwitch(
            metered = networkStatus::isMetered,
            // Only something actually SHOWING video can be downgraded. Already-audio, paused and
            // stopped all report nothing here, so none of them provokes a pointless re-prepare.
            playingVideoId = {
                playbackController.state.value?.takeIf { it.hasVideo && it.isPlaying }?.itemId
            },
            switchToAudio = {
                // The real "listen only" mode, not a private one — so the player's own toggle is
                // the undo, and the choice survives to the next item as the person would expect.
                appPreferences.setPlaybackMode(PlaybackMode.AUDIO)
                playbackQueue.replayCurrent(playbackController.state.value?.positionMs ?: 0L)
            },
            announce = { id ->
                dataSaverNotifier.switchedToAudio(
                    playbackController.state.value?.title ?: id.value,
                )
            },
            scope = applicationScope,
        ).also { it.start() }
        StallWatchdog(
            states = playbackController.state,
            advance = { playbackQueue.playNextInQueue() },
            // The same recovery ExpiredStreamRecovery uses. A hung request raises no error, so
            // that watcher never fires for it — this one has to reach the same rescue itself.
            replay = { positionMs -> playbackQueue.replayCurrent(positionMs) },
            isEnabled = autoPlayNextEnabled,
            scope = applicationScope,
        ).start()
        // Resolving one item ahead, defined ONCE. Two things want it for the same reason —
        // an extraction costs 20-25s on a phone, and any second of that spent while the user is
        // already in silence is a second wasted — so the prefetcher (near the end of an item)
        // and the recovery (the moment a stream fails) share this rather than each keeping a
        // copy of "how do I resolve a video".
        // Exhaustive rather than a cast, so a new kind of playable cannot quietly get no
        // preparation at all — which is what happened to torrents: this was written when only a
        // video cost anything to get ready, and a torrent's audio URL now has ~25s of ffmpeg on
        // the home server behind it. The compiler asks the question now.
        val prefetchOne: suspend (PlayableItem) -> Unit = ::readyAgain
        // Videos resolve just-in-time, which meant yt-dlp's ~7 seconds landed in the silence
        // AFTER the previous item ended. Same rule, started a minute earlier.
        NextUpPrefetcher(
            states = playbackController.state,
            nextUp = playbackQueue::peekNext,
            prefetch = prefetchOne,
            scope = applicationScope,
        ).start()
        startStreamRecovery(prefetchOne)
        DiagnosticsUploader(context, httpClient, applicationScope).uploadPending()
        // Kept current so [diagnosticState] can answer "was it downloaded?" without blocking.
        downloadManager.observeDownloads()
            .onEach { latestDownloadStates = it }
            .launchIn(applicationScope)
    }

    /** Everything that gets a dead stream playing again, wired in one place. */
    private fun startStreamRecovery(prefetchOne: suspend (PlayableItem) -> Unit) {
        StreamRecovery(
            failures = playbackController.streamFailures,
            replay = playbackQueue::replayCurrent,
            moveOn = { playbackQueue.playNextInQueue() },
            autoPlayNext = autoPlayNextEnabled,
            // The last thing to try before abandoning the item: the copy already on the disk.
            playWithoutTheStream = playbackQueue::playCurrentWithoutItsStream,
            // The sound without the picture, tried after the disk and before giving up. There is no
            // durable VIDEO stream to be had for a long video — see StreamRecovery's parameter doc —
            // so this is the difference between losing the picture and losing the item.
            playWithoutThePicture = { at -> playbackQueue.playCurrentWithoutThePicture(at) },
            // The rung that keeps the picture. Deliberately NOT gated on the sabrPlayback setting:
            // that setting decides whether SABR is the primary route (it should not be — 1080p30, no
            // seeking), while this is the last chance to show a picture at all.
            playOverSabr = { at -> playbackQueue.playCurrentOverSabr(at) },
            // Choosing something by hand is a new stuck point. Without this the budget carried
            // over from the give-up, and report 0.1.383's two hand-taps of a failed video were
            // each refused on their first error, with no retry and no re-resolve.
            freshStarts = playbackQueue.freshStarts,
            isPlaying = { id ->
                playbackController.state.value?.let { it.itemId == id && it.isPlaying } == true
            },
            forgetResolved = playbackQueue::forgetResolved,
            // Started on the first failure, so the 20-25s extraction overlaps the retries
            // instead of following them. Report 0.1.277: 58s of silence, 28 of it after the app
            // had already given up on the dead stream.
            prefetchNext = { playbackQueue.peekNext()?.let { prefetchOne(it) } },
            awaitNetwork = networkStatus::awaitOnline,
            scope = applicationScope,
        ).start()
        // A SABR stall must stop that item being resolved over SABR again. Without this, recovery
        // forgets the resolution, `extractAndCache` asks overSabr() first (the setting is on), and the
        // retry goes straight back to the route that just stalled -- burning the budget before the
        // ladder can fall through to extraction, which is the route that can seek.
        applicationScope.launch {
            playbackController.streamFailures.collect { failure ->
                if (failure.sabrStalled) videoResolver.sabrStalled(failure.itemId)
            }
        }
    }

    /**
     * The most recent download states, for diagnostics only.
     *
     * Volatile because it is written from the app scope and read on whichever thread is reporting
     * — often one that has just crashed.
     */
    @Volatile
    private var latestDownloadStates: Map<MediaItemId, DownloadState> = emptyMap()

    /**
     * Everything a report carries about the app's state at the moment it was sent. Its own class:
     * it is the largest single thing the container did, and none of it is wiring.
     */
    private val diagnosticSnapshot: DiagnosticSnapshot by lazy {
        DiagnosticSnapshot(
            playbackController = playbackController,
            playbackQueue = playbackQueue,
            accountSubscriptions = accountSubscriptions,
            live = DiagnosticSnapshot.Live(
                downloadStates = { latestDownloadStates },
                settings = { appPreferences.settings.value },
                accountSync = { progressOutboxDrain.status.value to latestPendingAccountUpdates },
                isMetered = networkStatus::isMetered,
            ),
        )
    }

    private fun diagnosticState(): Map<String, String> = diagnosticSnapshot.capture()

    override fun sendDiagnostics(note: String) {
        crashReporter.reportDiagnostics(note)
        DiagnosticsUploader(context, httpClient, applicationScope).uploadPending()
    }

    override fun startQueueAutoDownload() {
        QueueAutoDownloader(
            queue = playbackQueue.state,
            downloads = downloadManager,
            scope = applicationScope,
            isEnabled = { appPreferences.settings.value.autoDownloadQueue },
            isAllowedOnThisNetwork = {
                autoDownloadAllowedNow()
            },
            // The one place pillar routing lives. A torrent has no audio-only fetch — the server's
            // audio is HLS — so an automatic "fetch the audio" would silently pull a whole film.
            fetchesAudioOnly = { it.hasAudioOnlyFetch },
            // NOT the cursor: a peek leaves it at -1 by design, and a peek is how the reported case
            // arose — a Ms Rachel video 52 minutes in, first in a 47-item queue, with no download
            // event at all while the pass ground through a 403ing podcast ahead of it.
            playingNow = { playbackQueue.nowPlaying.value?.item?.id },
        ).start()
    }

    override val playbackQueue: PlaybackQueue by lazy {
        PlaybackQueue(
            playbackController,
            videoPlaybackLauncher,
            applicationScope,
            queueStore,
            onQueuedByUser = ::saveToWatchLater,
            // The same rule the video path uses, so Listen means one thing on both pillars.
            audioPreferred = ::audioPlaybackPreferred,
            // Asked per play, so an item downloaded after it was queued still plays from disk.
            localCopy = { id -> realCopyFor(id) },
            // Errs toward "there is a network" only when it can genuinely tell; NetworkStatus
            // itself errs the other way when unsure, which is the safe direction for data.
            offline = ::isOffline,
            refresh = { item -> readyAgain(item) },
        )
    }

    override val localPlaylistStore: LocalPlaylistStore by lazy {
        RoomLocalPlaylistStore(database.localPlaylistDao())
    }

    override val sourceLocator: SourceLocator by lazy {
        DefaultSourceLocator(podcastRepository, ytDlpEngine)
    }

    override val groupFeed: GroupFeed by lazy {
        // The only place a group's fanout knows pillars exist — exhaustive over the sealed
        // MediaSource, so a third pillar cannot be added without it failing to compile. Same
        // shape as RoutedDownloadStrategy, deliberately.
        GroupFeed(
            RoutedSourceItems(
                video = ChannelSourceItems(channelRepository),
                podcast = PodcastSourceItems(podcastRepository),
            ),
        )
    }

    override val playHistoryStore: PlayHistoryStore by lazy {
        RoomPlayHistoryStore(database.playHistoryDao())
    }

    private val youTubeWatchHistory: YouTubeWatchHistory by lazy {
        HttpYouTubeWatchHistory(
            youTubeAccount,
            httpClient,
            innerTubeClient,
            signatureTimestamps,
        )
    }

    /**
     * Asks YouTube for a video as the signed-in account, for the ones it refuses anonymously.
     *
     * Returns null when signed out rather than throwing, so the anonymous path's failure stands
     * as the reason — "you are not signed in" is a different, better message than a token error.
     */
    private val accountPlayer: InnerTubePlayerStreams.AccountPlayer by lazy {
        InnerTubePlayerStreams.AccountPlayer { videoId ->
            // Signed out is the ordinary case, not an error: the anonymous failure already said
            // why the video would not play, and that message is the better one to keep.
            val token = (
                runCatching { youTubeAccount.accessToken() }.getOrNull()
                    as? AccessTokenResult.Available
                )?.token ?: return@AccountPlayer null
            val stamp = runCatching { signatureTimestamps.current() }.getOrNull() ?: return@AccountPlayer null
            // DOWNGRADED first, and that ordering is measured. Both clients answer OK for the
            // same rated video, but the current one withholds all but ONE stream (SABR) while
            // the downgraded one returns seven — so preferring the current client here means
            // reaching an age-restricted video and then watching it at 360p. This path only runs
            // when the anonymous attempt already failed, so there is no ordinary video to lose.
            withPlayableStreams(videoId, "downgraded TV") {
                innerTubeClient.playerDowngradedTv(videoId, stamp, token)
            } ?: withPlayableStreams(videoId, "TV") { innerTubeClient.playerAsAccount(videoId, stamp, token) }
        }
    }

    /**
     * A player response ONLY if something in it can actually be fetched, with `n` solved.
     *
     * The distinction this draws is the one that matters, and getting it wrong shipped a feature
     * that did nothing (caught on an emulator 2026-08-01, not by any test). YouTube answers
     * `status=OK` for an age-restricted video on the CURRENT TV client — while withholding the
     * streams, one SABR-degraded URL out of seven. Treating that OK as success short-circuited
     * the downgraded client that would have worked, so the video "resolved" and then refused to
     * play, which reads to a user exactly like the feature not existing.
     *
     * So success is defined as **a format we can fetch**, never as a status. Both attempts run
     * through here for that reason: the same trap catches the first one too, since its lone URL
     * carries an unsolved `n` and would 403 on playback.
     */
    private suspend fun withPlayableStreams(
        videoId: String,
        client: String,
        request: suspend () -> InnerTubeResponse,
    ): PlayerResult.Success? {
        val response = runCatching { request() }.getOrNull()
        val parsed = (response as? InnerTubeResponse.Success)?.body?.let(PlayerResponseParser::parse)
        if (parsed !is PlayerResult.Success) {
            // WHAT it said, not just that it failed. Concluding "refused" from a null was the
            // mistake that made age restriction look impossible for two rounds.
            Diag.log("resolve", "$videoId as $client -> ${parsed ?: response}")
            return null
        }
        val playerUrl = runCatching { signatureTimestamps.playerScriptUrl() }.getOrNull() ?: run {
            Diag.warn("resolve", "$videoId resolved but no player script to solve its n parameters")
            return null
        }
        val playable = parsed.streaming.withSolvedN(nSolver, playerUrl)
        // DELIBERATELY still `directlyPlayable`, not `playableSomehow`. The sibling gate in
        // InnerTubePlayerStreams was widened to accept a SABR-only response; this one must not be.
        // Its whole job is to keep trying CLIENTS, and the current TV client answers OK for an
        // age-restricted video while withholding the streams -- accepting that as success is what
        // short-circuited the downgraded client that returns seven fetchable URLs, and shipped a
        // feature that resolved and then would not play (2026-08-01).
        if (playable.directlyPlayable.isEmpty()) {
            Diag.log("resolve", "$videoId as $client -> OK but nothing fetchable; trying the next client")
            return null
        }
        Diag.log("resolve", "$videoId as $client -> ${playable.directlyPlayable.size} fetchable format(s)")
        return parsed.copy(streaming = playable)
    }

    /**
     * Solves `n` with the QuickJS the app already bundles for yt-dlp.
     *
     * Wired here because this is the only place allowed to know both libraries: `:lib:innertube`
     * declares the port and `:lib:ytdlp` owns the JavaScript runtime, and the two stay
     * independent of each other so both remain separately publishable.
     */
    private val nSolver: NSolver by lazy {
        NSolver { challenges, playerUrl -> ytDlpEngine.solveN(challenges, playerUrl) }
    }

    private val signatureTimestamps by lazy { HttpSignatureTimestampSource(httpClient) }

    override val appPreferences: AppPreferences by lazy { SharedPrefsAppPreferences(context) }

    private val networkStatus by lazy { NetworkStatus(context) }

    /**
     * Whether playback should be audio-only right now.
     *
     * Auto means "video on Wi-Fi, audio on mobile data". ONE definition, read by the video
     * launcher and by the queue's torrent path alike — Listen has to mean the same thing on
     * both pillars, and two copies of this rule would eventually disagree about a video.
     */
    /**
     * The download record for [id], but only when the file it names is really there.
     *
     * A RECORD is not a FILE. Nothing revalidates a `Downloaded` row, so a copy the person deleted from
     * storage -- or a zero-byte one left by an older build -- still made `routeNow` prefer the disk,
     * where Media3 threw `FileDataSourceException` (an `IOException`, so recovery classed it
     * `Unreachable`), the retries replayed the same missing file, and the disk rung re-routed to it and
     * reset the budget. The item never played AND the queue never advanced past it, behind a green tick,
     * with deleting the download the only escape.
     *
     * The sibling rule already exists in `PlayerBackedDownloadStrategy`: "a file that exists is not a
     * file that plays". This is the read-side half, and it remediates records already on the device --
     * nothing else does.
     */
    private suspend fun realCopyFor(id: MediaItemId): LocalCopy? {
        val recorded = (downloadManager.observe(id).first() as? DownloadState.Downloaded) ?: return null
        val file = java.io.File(recorded.localPath)
        if (file.exists() && file.length() > 0) return LocalCopy(recorded.localPath, recorded.audioOnly)
        val why = if (file.exists()) "is empty" else "is missing"
        Diag.warn(
            "playback",
            "${id.value} is recorded as downloaded but ${recorded.localPath} $why — " +
                "ignoring the record and streaming instead",
        )
        return null
    }

    override val listeningNow: Boolean
        get() = listeningIn(appPreferences.settings.value.playbackMode, networkStatus.isMetered())

    private fun audioPlaybackPreferred(): Boolean = listeningNow

    /**
     * Whether automatic downloads may run on the connection we are on right now.
     *
     * ONE definition, read by both the downloader and the queue screen's summary — the screen
     * explains why nothing is downloading, and a second copy of this rule would eventually
     * explain the wrong reason.
     */
    override fun autoDownloadAllowedNow(): Boolean =
        !appPreferences.settings.value.autoDownloadWifiOnly || !networkStatus.isMetered()

    private val dataSaverNotifier by lazy { DataSaverNotifier(context) }

    /**
     * The one "get this ready" routing, shared by the prefetcher and the stall rescue.
     *
     * Defined here rather than inline in both, so a torrent gaining a way to be warmed cannot be
     * picked up by one caller and missed by the other.
     */
    private suspend fun readyAgain(item: PlayableItem) {
        when (val handle = item.handle) {
            // A video's stream URL does not exist until it resolves, so the bytes are nominated
            // HERE, on the far side of the resolution, rather than in preloadBytesOf with the rest.
            is PlayHandle.Video -> videoResolver.prefetch(handle.watchUrl, item.item.sourceId)
                ?.let { resolved ->
                    // ASKED, not guessed. This used to pick a stream by its own rule and disagreed
                    // with the launcher's on every video with a quality ladder, so the bytes were
                    // held and then thrown away — twelve nominations, twelve wasted, in 0.1.390.
                    nominatePreload(item.item.id, videoPlaybackLauncher.urlThatWouldPlay(resolved))
                }
            is PlayHandle.LocalVideo -> Unit
            is PlayHandle.Podcast -> handle.audioUrl?.let { homeTorrentServer?.warmAudio(it) }
        }
        preloadBytesOf(item)
    }

    /**
     * Holds the first seconds of the next item's audio, on Wi-Fi only.
     *
     * Dewi, 2026-08-02: *"defo yes on wifi, but maybe not on mobile please"*. Thirty seconds is flat
     * in TIME and eight times apart in BYTES across the pillars — roughly 0.5MB for a podcast, 8MB
     * for 1080p video — so it is spent only where it is free.
     *
     * `isMetered()` errs toward metered when it cannot tell, which is the right way to be wrong for
     * something that spends data.
     */
    private fun preloadBytesOf(item: PlayableItem) {
        // A local copy needs nothing, and a video's URL is not known until it resolves — so this
        // covers exactly what has a playable URL in hand right now.
        val url = when (val handle = item.handle) {
            // NOT `localPath?.let { null } ?: …` — an elvis cannot tell a deliberate null from an
            // absent one, so that spelling fell straight through and preloaded a file already on
            // the device. Caught by PreloadOnWifiOnlyTest before it shipped.
            is PlayHandle.Podcast -> if (handle.localPath != null) null else handle.audioUrl ?: item.item.mediaUrl
            is PlayHandle.LocalVideo -> null
            is PlayHandle.Video -> null
        } ?: return
        nominatePreload(item.item.id, url)
    }

    /**
     * The one place bytes are actually asked for, so the Wi-Fi gate cannot be bypassed.
     *
     * [itemId] travels with the URL because it is what the preloader releases on — a stream URL is
     * re-signed per resolve and is routinely not the one the item ends up playing, so keying on it
     * meant nothing was ever released. See `PlaybackController.preloadNext`.
     */
    private fun nominatePreload(itemId: MediaItemId, url: HttpUrl?) {
        if (url == null) return
        if (networkStatus.isMetered()) {
            Diag.log("preload", "not preloading $itemId: on metered data")
            return
        }
        playbackController.preloadNext(itemId, url)
    }

    override fun isOffline(): Boolean = !networkStatus.isOnline()

    override val videoPlaybackLauncher: VideoPlaybackLauncher by lazy {
        VideoPlaybackLauncher(
            videoResolver,
            playbackController,
            youTubeWatchHistory,
            playHistory = playHistoryStore,
            // Auto means "video on Wi-Fi, audio on mobile data"; the launcher only ever
            // sees the resolved answer.
            audioPreferred = ::audioPlaybackPreferred,
            preferredMaxHeight = {
                val settings = appPreferences.settings.value
                if (networkStatus.isMetered()) settings.cellularMaxHeight else settings.wifiMaxHeight
            },
            choices = streamChoices,
            onResolved = { playbackQueue.adoptFacts(it) },
        )
    }

    /** Progress the account has not been told about yet; drained by [progressOutboxDrain]. */
    private val accountProgressOutbox: AccountProgressOutbox by lazy {
        RoomAccountProgressOutbox(database.accountProgressOutboxDao())
    }

    private val progressOutboxDrain: ProgressOutboxDrain by lazy {
        ProgressOutboxDrain(accountProgressOutbox, youTubeWatchHistory, applicationScope)
    }

    private val watchHistorySync: WatchHistorySync by lazy {
        WatchHistorySync(playbackController, accountProgressOutbox, progressOutboxDrain, applicationScope)
    }

    /** For the diagnostics snapshot, which must never block on the database. */
    @Volatile
    private var latestPendingAccountUpdates: Int = -1

    override fun startWatchHistorySync() {
        watchHistorySync.start()
        accountProgressOutbox.observePendingCount()
            .onEach { latestPendingAccountUpdates = it }
            .launchIn(applicationScope)
        // Whatever the last run could not send goes first; then again whenever the network comes
        // back, which is the moment offline listening can finally be reported. Sampled rather than
        // subscribed because NetworkStatus.awaitOnline returns at once while online, and a kick costs
        // a network round trip per held item, so only a genuine offline-to-online edge earns one.
        progressOutboxDrain.kick()
        applicationScope.launch {
            var wasOnline = networkStatus.isOnline()
            while (true) {
                delay(NETWORK_EDGE_SAMPLE_MS)
                val online = networkStatus.isOnline()
                if (online && !wasOnline) {
                    Diag.log("yt-sync", "network is back — trying to send held progress")
                    progressOutboxDrain.kick()
                }
                wasOnline = online
            }
        }
    }

    override fun refreshSubscriptions() {
        accountSubscriptions.refresh()
    }

    override val accountSubscriptions: AccountSubscriptions by lazy {
        AccountSubscriptions(
            subscriptions = HttpYouTubeSubscriptions(youTubeAccount, innerTubeClient),
            actions = youTubeActions,
            account = youTubeAccount,
            scope = applicationScope,
        )
    }

    override val youTubeAccount: YouTubeAccount by lazy {
        YouTubeAccount(
            auth = HttpYouTubeAuth(httpClient),
            store = SharedPrefsTokenStore(context),
        )
    }

    /**
     * The token is supplied as a LAMBDA, not a value: the account is built later in this graph and
     * a token expires anyway, so it is fetched per request. See `Identity` for where it is attached
     * and, more importantly, where it must not be.
     */
    private val innerTubeClient by lazy { InnerTubeClient(httpClient, accountToken = ::searchToken) }

    override val youTubeFeeds: YouTubeFeeds by lazy {
        HttpYouTubeFeeds(youTubeAccount, innerTubeClient)
    }

    override val youTubeComments: YouTubeComments by lazy {
        HttpYouTubeComments(innerTubeClient)
    }

    override val youTubeRelated: YouTubeRelated by lazy {
        HttpYouTubeRelated(innerTubeClient)
    }

    override val youTubeChannel: YouTubeChannel by lazy {
        HttpYouTubeChannel(innerTubeClient)
    }

    override val youTubeActions: YouTubeActions by lazy {
        HttpYouTubeActions(youTubeAccount, innerTubeClient)
    }

    override val youTubePlaylists: YouTubePlaylists by lazy {
        HttpYouTubePlaylists(youTubeAccount, innerTubeClient)
    }

    override val bellSeenTracker: SeenItemsTracker by lazy {
        SharedPrefsSeenItemsTracker(context, namespace = "bell")
    }

    override val contentRefresher: ContentRefresher by lazy {
        ContentRefresher(
            sources = listOf(
                PodcastSubscriptionItemsSource(podcastRepository),
                YouTubeSubscriptionItemsSource(youTubeFeeds),
            ),
            tracker = SharedPrefsSeenItemsTracker(context, namespace = "notifications"),
        )
    }

    override val subscriptionImporter: SubscriptionImporter by lazy {
        SubscriptionImporter(
            parser = SubscriptionImportParser(),
            exporter = OpmlExporter(),
            podcasts = podcastRepository,
            channels = accountSubscriptions,
            channelResolver = channelRepository,
        )
    }

    private companion object {
        /** How often the network is sampled for an offline-to-online edge; a kick per edge, not per tick. */
        const val NETWORK_EDGE_SAMPLE_MS = 15_000L

        /** Matches AccountResumePositions' staleness window. */
        const val ACCOUNT_WATCHED_REFRESH_MS = 5 * 60 * 1_000L

        const val HTTP_TIMEOUT_SECONDS = 20L

        /** Matches the home server's own 180s ceiling for a fan-out indexer search. */
        const val TORRENT_SEARCH_TIMEOUT_SECONDS = 180L
    }
}

/**
 * The phone's languages, best first — which audio track a video should play in.
 *
 * The whole locale list, not just the first: a phone set to English then Welsh should accept a
 * Welsh original over a French dub. Region is dropped (`en-GB` becomes `en`) because YouTube
 * labels its tracks by region and a track in `en-US` is still the English one.
 */
private fun deviceLanguages(): List<String> {
    val locales = LocaleListCompat.getDefault()
    return (0 until locales.size()).mapNotNull { locales[it]?.language?.ifBlank { null } }.distinct()
}
