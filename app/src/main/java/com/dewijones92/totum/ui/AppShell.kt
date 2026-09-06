package com.dewijones92.totum.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.busy.BusyBar
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.di.fake.FakeAppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.ReelStart
import com.dewijones92.totum.innertube.playlists.Playlist
import com.dewijones92.totum.navigation.TopLevelDestination
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.theme.TotumTheme
import com.dewijones92.totum.ui.common.ItemActionSheet
import com.dewijones92.totum.ui.common.LocalExpandPlayer
import com.dewijones92.totum.ui.common.MiniPlayerBar
import com.dewijones92.totum.ui.common.ProvidePlayStates
import com.dewijones92.totum.ui.common.RequestNotificationPermissionOnFirstPlay
import com.dewijones92.totum.ui.library.LibraryScreen
import com.dewijones92.totum.ui.motion.sharedXAxis
import com.dewijones92.totum.ui.player.CommentReplies
import com.dewijones92.totum.ui.player.FullPlayerOverlay
import com.dewijones92.totum.ui.player.LocalVideoBounds
import com.dewijones92.totum.ui.player.PictureInPictureEffect
import com.dewijones92.totum.ui.player.PlaybackToggles
import com.dewijones92.totum.ui.player.QualityControl
import com.dewijones92.totum.ui.player.QueueControls
import com.dewijones92.totum.ui.player.VideoBounds
import com.dewijones92.totum.ui.player.WatchViewModel
import com.dewijones92.totum.ui.player.rememberIsInPictureInPicture
import com.dewijones92.totum.ui.player.rememberWatchActions
import com.dewijones92.totum.ui.podcasts.PodcastsScreen
import com.dewijones92.totum.ui.queue.QueueScreen
import com.dewijones92.totum.ui.search.SearchScreen
import com.dewijones92.totum.ui.shorts.ShortsReelScreen
import com.dewijones92.totum.ui.videos.VideosScreen
import com.dewijones92.totum.video.VideoPlaybackLauncher
import kotlinx.coroutines.launch

/**
 * Top-level scaffold: bottom navigation across the app's pillars with
 * animated transitions between them.
 */
@Composable
fun AppShell(container: AppContainer, modifier: Modifier = Modifier) {
    var selected by rememberSaveable { mutableStateOf(TopLevelDestination.Videos) }
    var showFullPlayer by rememberSaveable { mutableStateOf(false) }
    var shortsReel by remember { mutableStateOf<ReelStart?>(null) }
    // "Go to channel" works from ANY row because the shell hosts the destination once.
    var shellChannel by remember { mutableStateOf<MediaSource.VideoChannel?>(null) }
    // A playlist opened from the channel overlay. This passed `{}` and swallowed the tap: a
    // channel reached via "go to channel" listed its playlists and none of them would open.
    var shellPlaylist by remember { mutableStateOf<Playlist?>(null) }
    val playbackState by container.playbackController.state.collectAsStateWithLifecycle()
    val controller = container.playbackController
    val watchViewModel: WatchViewModel = viewModel(factory = WatchViewModel.factory(container))

    RequestNotificationPermissionOnFirstPlay(playbackActive = playbackState != null)
    // The stage reports where the picture is, so the system animates from it rather
    // than cross-fading the whole app into the floating window.
    val videoBounds = remember { VideoBounds() }
    // The mini player's skip is a UI gesture; the advance itself is app-scoped elsewhere.
    val skipScope = rememberCoroutineScope()
    val inPip = floatingWindowState(playbackState, controller, videoBounds)
    WatchBindings(playbackState, watchViewModel)

    // A floating window is centimetres across: the nav bar, mini player and scrolling
    // description would leave no room for the picture, so it renders alone.
    if (inPip) {
        FloatingVideo(playbackState, controller.player, modifier)
        return
    }

    CompositionLocalProvider(
        LocalVideoBounds provides videoBounds,
        // Peeking opens the player, and the shell is what owns "open".
        LocalExpandPlayer provides { showFullPlayer = true },
    ) {
        ProvidePlayStates(container, onOpenChannel = { shellChannel = it }) {
            Box(modifier = modifier.fillMaxSize()) {
                Scaffold(
                    bottomBar = {
                        BottomBar(
                            playbackState,
                            selected,
                            controller::togglePlayPause,
                            onExpand = { showFullPlayer = true },
                            onSelect = { selected = it },
                            onSkipNext = { skipScope.launch { container.playbackQueue.playNextInQueue() } },
                        )
                    },
                ) { innerPadding ->
                    TopLevelContent(container, selected, { shortsReel = it }, Modifier.padding(innerPadding))
                }

                // Full player overlays the whole app (above the mini player + nav) when
                // expanded; the mini player keeps the audio/video running underneath.
                playbackState?.takeIf { showFullPlayer }?.let { state ->
                    FullPlayerHost(state, controller, container, watchViewModel) { showFullPlayer = false }
                }

                // The Shorts reel is a full-screen overlay (above the nav + mini player),
                // so vertical swipes page between shorts without the app chrome in the way.
                shortsReel?.let { ShortsReelScreen(container, it, onBack = { shortsReel = null }) }
                // Same as the Videos tab's overlays: back should close the channel, not quit.
                ShellOverlays(
                    container = container,
                    channel = shellChannel,
                    onCloseChannel = { shellChannel = null },
                    playlist = shellPlaylist,
                    onOpenPlaylist = { shellPlaylist = it },
                    onClosePlaylist = { shellPlaylist = null },
                )
                // Last in the Box so it draws over everything, including the full player and
                // any overlay: "is the app doing something" is a question worth answering
                // from whatever screen the user is on.
                BusyBar(Modifier.safeDrawingPadding())
            }
        }
    }
}

/**
 * Binds the watch view model to the current video — comments, related, the like button.
 *
 * Genuinely a UI concern, unlike advancing, which is app-scoped so it survives the screen
 * going off. None of this matters with the screen off, so it belongs here.
 */
@Composable
private fun WatchBindings(
    state: PlaybackState?,
    watchViewModel: WatchViewModel,
) {
    LaunchedEffect(state?.itemId, state?.hasVideo) {
        state?.takeIf { it.hasVideo }?.let { watchViewModel.bind(it.itemId.value) }
    }
}

/** The mini player sitting above the tabs — one bar, so neither appears without the other. */
@Composable
private fun BottomBar(
    state: PlaybackState?,
    selected: TopLevelDestination,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit,
    onSelect: (TopLevelDestination) -> Unit,
    onSkipNext: () -> Unit,
) {
    Column {
        state?.let {
            MiniPlayerBar(
                state = it,
                onTogglePlayPause = onTogglePlayPause,
                onExpand = onExpand,
                onSkipNext = onSkipNext,
            )
        }
        TopLevelNavigationBar(
            selected,
            // Logged because a real report could not answer "did this happen when I switched
            // tabs?" — nothing recorded that the user had, so the question was unanswerable.
            onSelect = { destination ->
                Diag.log("nav", "tab $selected -> $destination")
                onSelect(destination)
            },
        )
    }
}

@Composable
private fun TopLevelNavigationBar(selected: TopLevelDestination, onSelect: (TopLevelDestination) -> Unit) {
    NavigationBar {
        TopLevelDestination.entries.forEach { destination ->
            val isSelected = destination == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(destination) },
                icon = {
                    val icon = if (isSelected) destination.selectedIcon else destination.unselectedIcon
                    // A hair larger when selected — the filled/outlined swap alone is a small
                    // signal, and animating the size makes which tab you are on readable at a
                    // glance rather than something you have to look for.
                    val scale by animateFloatAsState(
                        targetValue = if (isSelected) SELECTED_ICON_SCALE else 1f,
                        label = "nav-icon",
                    )
                    Icon(
                        imageVector = icon,
                        // Described, not null. The label below is decoration that a screen reader
                        // may or may not reach; this is the tab's actual name.
                        contentDescription = stringResource(destination.labelRes),
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                },
                label = { Text(stringResource(destination.labelRes)) },
            )
        }
    }
}

/** Hosts the full-player overlay, wiring it to the one playback controller. */
@Composable
private fun FullPlayerHost(
    state: PlaybackState,
    controller: PlaybackController,
    container: AppContainer,
    watchViewModel: WatchViewModel,
    onDismiss: () -> Unit,
) {
    val comments by watchViewModel.comments.collectAsStateWithLifecycle()
    val replies by watchViewModel.replies.collectAsStateWithLifecycle()
    val related by watchViewModel.related.collectAsStateWithLifecycle()
    val sleepTimer by container.sleepTimer.state.collectAsStateWithLifecycle()
    val quality by watchViewModel.quality.collectAsStateWithLifecycle()
    val queueState by container.playbackQueue.state.collectAsStateWithLifecycle()
    val upNext = queueState.upNext
    var showItemSheet by remember { mutableStateOf(false) }
    // What is PLAYING, not where the cursor is. Those differ for a peek and for anything
    // played before the queue hydrated, and this used to read the cursor — so the player's
    // item actions (add to queue, play next, add to playlist) silently vanished for exactly
    // those items, leaving quality and speed as the only things you could reach. Falls back
    // to the cursor for a session where the queue itself did not start playback.
    val nowPlaying by container.playbackQueue.nowPlaying.collectAsStateWithLifecycle()
    val playing = nowPlaying ?: queueState.current?.item
    val currentIndex = queueState.currentIndex
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()

    FullPlayerOverlay(
        state = state,
        player = controller.player,
        comments = comments,
        replies = CommentReplies(
            threads = replies,
            onToggle = watchViewModel::toggleReplies,
            onLoadMore = watchViewModel::loadMoreReplies,
        ),
        related = related,
        watchActions = rememberWatchActions(watchViewModel),
        quality = qualityControl(quality, watchViewModel),
        sleepTimer = sleepTimer,
        onDismiss = onDismiss,
        onPlayRelated = watchViewModel::playRelated,
        onStartSleep = container.sleepTimer::start,
        onStopSleepAfterItem = container.sleepTimer::stopAfterCurrentItem,
        onCancelSleep = container.sleepTimer::cancel,
        onTogglePlayPause = controller::togglePlayPause,
        onSeekTo = controller::seekTo,
        onSeekBackward = controller::seekBackward,
        onSeekForward = controller::seekForward,
        onSetSpeed = controller::setSpeed,
        onSetSubtitleLanguage = controller::setSubtitleLanguage,
        toggles = playbackToggles(state, controller, container, settings),
        queue = upNextControls(container.playbackQueue, upNext, currentIndex),
        onMore = { showItemSheet = true }.takeIf { playing != null },
    )

    PlayingItemSheet(playing, showItemSheet) { showItemSheet = false }
}

/**
 * The player's up-next list shows what follows the cursor, so its indices are offset from
 * the queue's own — done here once rather than inline at the call site.
 */
private fun upNextControls(queue: PlaybackQueue, upNext: List<QueueEntry>, currentIndex: Int) =
    QueueControls(
        upNext = upNext,
        onPlay = { i -> queue.jumpTo(currentIndex + 1 + i) },
        onRemove = { i -> queue.removeAt(currentIndex + 1 + i) },
    )

/**
 * The SAME sheet the rows use, for whatever is playing — so the player can never offer less
 * than a long-press does. Wired to the current QUEUE entry, which carries the real item and
 * its handle rather than a PlaybackState reconstruction.
 *
 * Every action comes from the app-wide [ItemActions]. It used to re-implement download,
 * mark-played and go-to-channel here, which is the very duplication that made menus differ
 * between screens in the first place.
 */
@Composable
private fun PlayingItemSheet(
    playing: PlayableItem?,
    visible: Boolean,
    onDismiss: () -> Unit,
) {
    val item = playing?.item ?: return
    if (!visible) return
    ItemActionSheet(item, onDismiss)
}

private fun qualityControl(
    quality: VideoPlaybackLauncher.QualityState,
    watchViewModel: WatchViewModel,
) = QualityControl(
    options = quality.options,
    selectedId = quality.selectedId,
    onSelect = watchViewModel::selectQuality,
    canListen = quality.canListen,
    listening = quality.listening,
    onListen = watchViewModel::listen,
    onWatch = watchViewModel::watch,
    audioTracks = quality.audioTracks,
    audioLanguage = quality.audioLanguage,
    onSelectAudioTrack = watchViewModel::selectAudioTrack,
)

private fun playbackToggles(
    state: PlaybackState,
    controller: PlaybackController,
    container: AppContainer,
    settings: AppPreferences.Settings,
) = PlaybackToggles(
    skipSilence = state.skipSilence,
    onSetSkipSilence = controller::setSkipSilence,
    autoPlayNext = settings.autoPlayNext,
    onSetAutoPlayNext = container.appPreferences::setAutoPlayNext,
    sabrPlayback = settings.sabrPlayback,
    onSetSabrPlayback = container.appPreferences::setSabrPlayback,
    onSetVolumeBoost = controller::setVolumeBoost,
)

@Preview(showBackground = true)
@Composable
private fun AppShellPreview() {
    TotumTheme { AppShell(FakeAppContainer()) }
}

/**
 * The selected pillar's screen, cross-faded as the bottom navigation changes.
 *
 * Each destination keeps its own state while you are away from it. Switching tabs
 * tears the outgoing screen out of composition, so without this a glance at the queue
 * threw away a long scroll through subscriptions and dropped you back at the top —
 * which made the tabs feel unsafe to use. [rememberSaveableStateHolder] is what a
 * NavHost uses for the same job: state saved under a key per destination, restored when
 * that destination comes back.
 *
 * Applied around the whole `when` rather than per screen on purpose. A tab that has to
 * opt in is a bug waiting for the next tab to be added.
 */
@Composable
private fun TopLevelContent(
    container: AppContainer,
    selected: TopLevelDestination,
    onOpenShorts: (ReelStart) -> Unit,
    modifier: Modifier,
) {
    val stateHolder = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = selected,
        modifier = modifier,
        label = "top-level-destination",
        // Shared-axis rather than the default cross-fade-and-scale. Tabs sit in a row, so
        // moving right should look like moving right — a fade alone tells you something
        // changed but not which way you went, and the scale reads as a dialog opening.
        transitionSpec = { sharedXAxis(forward = targetState.ordinal > initialState.ordinal) },
    ) { destination ->
        stateHolder.SaveableStateProvider(destination.name) {
            Destination(container, destination, onOpenShorts)
        }
    }
}

/** The one place a destination maps to its screen. */
@Composable
private fun Destination(
    container: AppContainer,
    destination: TopLevelDestination,
    onOpenShorts: (ReelStart) -> Unit,
) {
    when (destination) {
        TopLevelDestination.Videos -> VideosScreen(container, onOpenShorts = onOpenShorts)
        TopLevelDestination.Podcasts -> PodcastsScreen(container)
        TopLevelDestination.Queue -> QueueScreen(container)
        TopLevelDestination.Search -> SearchScreen(container)
        TopLevelDestination.Library -> LibraryScreen(container)
    }
}

/**
 * Publishes picture-in-picture parameters for whatever is playing and reports whether the
 * app is currently floating. Always composed, so it tracks playback rather than only what
 * the full player happens to be showing.
 */
@Composable
private fun floatingWindowState(
    state: PlaybackState?,
    controller: PlaybackController,
    bounds: VideoBounds,
): Boolean {
    PictureInPictureEffect(
        hasVideo = state?.hasVideo == true,
        isPlaying = state?.isPlaying == true,
        aspectRatio = state?.videoAspectRatio,
        bounds = bounds,
        onTogglePlayPause = controller::togglePlayPause,
    )
    return rememberIsInPictureInPicture()
}

/**
 * The picture, alone, for the floating window — no chrome, no controls. PiP supplies its
 * own play/pause action in the window frame, so drawing our own would only cover video
 * that has very little room to begin with.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun FloatingVideo(state: PlaybackState?, player: androidx.media3.common.Player?, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        if (player != null && state?.hasVideo == true) {
            androidx.media3.ui.compose.PlayerSurface(
                player = player,
                surfaceType = androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Enough to notice, not enough to jump. */
private const val SELECTED_ICON_SCALE = 1.15f
