package com.dewijones92.totum.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.innertube.actions.VideoRating
import com.dewijones92.totum.innertube.comments.Comment
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.player.WatchViewModel.CommentsState
import com.dewijones92.totum.ui.player.WatchViewModel.PostState
import com.dewijones92.totum.ui.player.WatchViewModel.RelatedState
import com.dewijones92.totum.ui.player.WatchViewModel.RepliesState
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Leaves fullscreen once the player has settled on something with no picture — and not before.
 *
 * Only a video can be fullscreen, but "no video track" is also what the player reports for the
 * whole gap between items, and for a stream that failed and is being re-resolved. Which of the
 * three it is lives in [videoPresence]; this is only the waiting and the saying-so.
 */
@Composable
private fun EndFullscreenOnceSettledOnAudio(
    state: PlaybackState,
    fullscreen: Boolean,
    onLeave: () -> Unit,
) {
    val presence = state.videoPresence()
    LaunchedEffect(presence) {
        if (presence != VideoPresence.SETTLED_ON_AUDIO) {
            // Said out loud: an unexplained "fullscreen active=false" in a report cannot be told
            // apart from the user tapping the button, which is why the 0.1.374 diagnosis had to
            // come from reading code rather than from the log.
            if (fullscreen) Diag.log("fullscreen", "holding fullscreen — ${presence.describe(state)}")
            return@LaunchedEffect
        }
        delay(NO_VIDEO_GRACE_MS)
        if (fullscreen) Diag.log("fullscreen", "leaving fullscreen — ${presence.describe(state)}")
        onLeave()
    }
}

/**
 * Full "now playing" screen, opened from the mini player. Drives the one
 * [com.dewijones92.totum.playback.PlaybackController], so it serves podcast
 * episodes and videos identically.
 */
@Composable
fun FullPlayerOverlay(
    state: PlaybackState,
    player: Player?,
    comments: CommentsState,
    replies: CommentReplies,
    related: RelatedState,
    watchActions: WatchActions,
    quality: QualityControl,
    sleepTimer: SleepTimerState,
    onDismiss: () -> Unit,
    onPlayRelated: (MediaItem) -> Unit,
    onStartSleep: (Duration) -> Unit,
    onStopSleepAfterItem: () -> Unit,
    onCancelSleep: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSubtitleLanguage: (String?) -> Unit,
    onMore: (() -> Unit)?,
    toggles: PlaybackToggles,
    queue: QueueControls = QueueControls.None,
) {
    KeepScreenOnWhilePlayingVideo(active = state.hasVideo && state.isPlaying)
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    // `videoPlayer` is non-null only for a video; only a video can go fullscreen.
    val videoPlayer = player.takeIf { state.hasVideo }

    EndFullscreenOnceSettledOnAudio(state, fullscreen) { fullscreen = false }

    // Back exits fullscreen first, then closes the player. Rendered in the
    // activity's own window (not a Dialog), so landscape rotation for fullscreen
    // is handled by the same window that hosts the app — a Dialog sub-window
    // would stay portrait-sized and leave the video in a stale frame.
    val videoSettings = rememberVideoSettings(state, quality, onSetSpeed, onSetSubtitleLanguage)
    BackHandler { if (fullscreen) fullscreen = false else onDismiss() }
    FullscreenEffect(active = fullscreen)
    Surface(modifier = Modifier.fillMaxSize()) {
        // Bound to `player`, not `videoPlayer`, so the surface survives an item change:
        // rebuilding it across the gap is a visible flicker on every auto-advance. The
        // player itself is null only before the session connects, which is not that gap.
        if (fullscreen && player != null) {
            // Immersive: just the picture, filling the screen in landscape.
            VideoStageWithControls(
                state = state,
                player = player,
                settings = videoSettings,
                fullscreen = true,
                onToggleFullscreen = { fullscreen = false },
                onDismiss = onDismiss,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
            )
        } else {
            DraggablePlayerContent(
                state = state,
                videoPlayer = videoPlayer,
                comments = comments,
                replies = replies,
                related = related,
                watchActions = watchActions,
                quality = quality,
                sleepTimer = sleepTimer,
                queue = queue,
                onEnterFullscreen = { fullscreen = true },
                onDismiss = onDismiss,
                onPlayRelated = onPlayRelated,
                onStartSleep = onStartSleep,
                onStopSleepAfterItem = onStopSleepAfterItem,
                onCancelSleep = onCancelSleep,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
                onSetSpeed = onSetSpeed,
                onSetSubtitleLanguage = onSetSubtitleLanguage,
                onMore = onMore,
                toggles = toggles,
            )
        }
    }
}

/**
 * The scrolling player (stage + details). Dragging the stage down slides the
 * whole thing and, past a threshold, drops to the mini player — which keeps
 * playing, so you can carry on using the app over the running video or audio.
 * The drag handle is the stage only, so scrolling the details below is
 * unaffected.
 */
@Composable
private fun DraggablePlayerContent(
    state: PlaybackState,
    videoPlayer: Player?,
    comments: CommentsState,
    replies: CommentReplies,
    related: RelatedState,
    watchActions: WatchActions,
    quality: QualityControl,
    sleepTimer: SleepTimerState,
    queue: QueueControls,
    onEnterFullscreen: () -> Unit,
    onDismiss: () -> Unit,
    onPlayRelated: (MediaItem) -> Unit,
    onStartSleep: (Duration) -> Unit,
    onStopSleepAfterItem: () -> Unit,
    onCancelSleep: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetSubtitleLanguage: (String?) -> Unit,
    onMore: (() -> Unit)?,
    toggles: PlaybackToggles,
) {
    val drag = rememberStageDragDismiss(onDismiss)
    val videoSettings = rememberVideoSettings(state, quality, onSetSpeed, onSetSubtitleLanguage)
    // The screen takes its colour from what is playing — see [rememberPlayerBackdrop], including
    // the one switch that reverts it to a fixed brand gradient.
    val backdrop = rememberPlayerBackdrop(state.artworkUrl, MaterialTheme.colorScheme.primary)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(backdrop.modifier)
            .systemBarsPadding()
            .then(drag.contentOffset)
            .verticalScroll(rememberScrollState())
            // Vertical only: the video bleeds to the screen edges (a 24dp inset each
            // side wasted the width that matters most), while the text below keeps its
            // margin via CONTENT_PADDING.
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerStage(
            state = state,
            videoPlayer = videoPlayer,
            settings = videoSettings,
            dragHandle = { content -> Box(drag.handle) { content() } },
            onEnterFullscreen = onEnterFullscreen,
            onDismiss = onDismiss,
            onTogglePlayPause = onTogglePlayPause,
            onSeekTo = onSeekTo,
            onSeekBackward = onSeekBackward,
            onSeekForward = onSeekForward,
        )

        // Wrapped rather than padded per-child: PlayerDetails emits into this column's
        // scope, so one padded column around it keeps the text inset while the video above
        // reaches the screen edges.
        Column(
            modifier = Modifier.padding(horizontal = CONTENT_PADDING),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayerDetails(
                state = state,
                controlsOverlaid = videoPlayer != null,
                comments = comments,
                replies = replies,
                related = related,
                watchActions = watchActions,
                quality = quality,
                sleepTimer = sleepTimer,
                queue = queue,
                onPlayRelated = onPlayRelated,
                onStartSleep = onStartSleep,
                onStopSleepAfterItem = onStopSleepAfterItem,
                onCancelSleep = onCancelSleep,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
                onSetSpeed = onSetSpeed,
                onMore = onMore,
                toggles = toggles,
            )
        }
    }
}

/**
 * The picture (or the artwork, for audio). For video the transport and seek bar overlay
 * it, modern-player style and auto-hiding; audio keeps them below.
 */
@Composable
private fun ColumnScope.PlayerStage(
    state: PlaybackState,
    videoPlayer: Player?,
    settings: VideoSettings,
    /** Wraps the stage in the drag-to-dismiss handle; the details below must stay scrollable. */
    dragHandle: @Composable (@Composable () -> Unit) -> Unit,
    onEnterFullscreen: () -> Unit,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
) {
    if (videoPlayer != null) {
        dragHandle {
            VideoStageWithControls(
                state = state,
                player = videoPlayer,
                settings = settings,
                fullscreen = false,
                onToggleFullscreen = onEnterFullscreen,
                onDismiss = onDismiss,
                onTogglePlayPause = onTogglePlayPause,
                onSeekTo = onSeekTo,
                onSeekBackward = onSeekBackward,
                onSeekForward = onSeekForward,
            )
        }
    } else {
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.Start)) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
        }
        dragHandle { Box(Modifier.padding(horizontal = CONTENT_PADDING)) { ArtworkStage(state) } }
    }
}

/**
 * Everything below the stage: title/artist, the transport + seek bar when they
 * aren't overlaid on a video ([controlsOverlaid]), then speed, quality, like,
 * description and comments — the same for both pillars, gated by what applies.
 */
@Composable
private fun PlayerDetails(
    state: PlaybackState,
    controlsOverlaid: Boolean,
    comments: CommentsState,
    replies: CommentReplies,
    related: RelatedState,
    watchActions: WatchActions,
    quality: QualityControl,
    sleepTimer: SleepTimerState,
    queue: QueueControls,
    onPlayRelated: (MediaItem) -> Unit,
    onStartSleep: (Duration) -> Unit,
    onStopSleepAfterItem: () -> Unit,
    onCancelSleep: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onMore: (() -> Unit)?,
    toggles: PlaybackToggles,
) {
    Spacer(Modifier.height(if (state.hasVideo) 16.dp else 32.dp))
    TitleBlock(state, onMore)

    // Audio: seek + transport sit below the artwork (video has them overlaid).
    if (!controlsOverlaid) {
        Spacer(Modifier.height(48.dp))
        SeekBar(state, onSeekTo)
        Spacer(Modifier.height(24.dp))
        TransportControls(state, onTogglePlayPause, onSeekBackward, onSeekForward)
    }

    Spacer(Modifier.height(20.dp))
    SecondaryControls(
        state = state,
        controlsOverlaid = controlsOverlaid,
        quality = quality,
        sleepTimer = sleepTimer,
        toggles = toggles,
        onStartSleep = onStartSleep,
        onStopSleepAfterItem = onStopSleepAfterItem,
        onCancelSleep = onCancelSleep,
        onSetSpeed = onSetSpeed,
    )

    // Like / dislike / Watch Later — signed-in write actions for the current video.
    if (state.hasVideo && watchActions.canAct) {
        Spacer(Modifier.height(16.dp))
        WatchActionButtons(watchActions)
    }

    NotesChaptersAndSponsors(state, onSeekTo)

    // The user's up-next queue — both pillars, above the video-only related list.
    if (queue.upNext.isNotEmpty()) {
        Spacer(Modifier.height(32.dp))
        UpNextSection(queue)
    }

    // Related / up-next and comments live under the video, YouTube-style; audio
    // items have neither.
    if (state.hasVideo) {
        Spacer(Modifier.height(32.dp))
        RelatedSection(related, onPlayRelated)
        Spacer(Modifier.height(32.dp))
        CommentsSection(comments, watchActions, replies)
    }
}

/**
 * Test tag on the views/date line, so an instrumented test can assert the facts reached the page.
 *
 * They travel through the media session as extras, which is exactly the kind of handoff that
 * compiles and then silently carries nothing — see `PlayerMetadataTest`.
 */
const val PLAYER_METADATA_TAG: String = "player-views-and-date"

/** The up-next queue: a titled list, tap an entry to jump to it, X to remove it. */
@Composable
private fun UpNextSection(queue: QueueControls) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.queue_up_next_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        queue.upNext.forEachIndexed { index, queued ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { queue.onPlay(index) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = queued.item.item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { queue.onRemove(index) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.queue_remove))
                }
            }
        }
    }
}

/**
 * The scrollable extras below the controls: description/show notes, the chapter
 * list, and the SponsorBlock time ranges — all unified across both pillars.
 */
@Composable
private fun NotesChaptersAndSponsors(state: PlaybackState, onSeekTo: (Long) -> Unit) {
    val description = state.description
    if (!description.isNullOrBlank()) {
        Spacer(Modifier.height(24.dp))
        DescriptionSection(description, onSeekTo)
    }
    if (state.chapters.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        ChapterList(state.chapters, onSeekTo) // tap a chapter to jump; marked amber on the seek bar
    }
    if (state.skipSegments.isNotEmpty()) {
        Spacer(Modifier.height(24.dp))
        SponsorSegments(state.skipSegments) // sponsor time ranges, matching the green seek-bar strip
    }
}

/** The up-next queue and its interactions, for the full player's queue list. */
data class QueueControls(
    val upNext: List<QueueEntry>,
    val onPlay: (Int) -> Unit,
    val onRemove: (Int) -> Unit,
) {
    companion object {
        /** No queue (previews). */
        val None: QueueControls = QueueControls(emptyList(), {}, {})
    }
}

/** The signed-in write actions available on the watch page. */
data class WatchActions(
    val canAct: Boolean,
    val rating: VideoRating,
    val inWatchLater: Boolean,
    val onToggleLike: () -> Unit,
    val onToggleDislike: () -> Unit,
    val onToggleWatchLater: () -> Unit,
    val postState: PostState,
    val onPostComment: (String) -> Unit,
    val onPostHandled: () -> Unit,
) {
    companion object {
        /** No account connected: reading only. */
        val ReadOnly: WatchActions =
            WatchActions(false, VideoRating.NONE, false, {}, {}, {}, PostState.Idle, {}, {})
    }
}

/** Reply-thread interactions for the comments list — expand/collapse and paging. */
data class CommentReplies(
    val threads: Map<String, RepliesState>,
    val onToggle: (Comment) -> Unit,
    val onLoadMore: (String) -> Unit,
) {
    companion object {
        /** Replies disabled (previews / no interactions). */
        val None: CommentReplies = CommentReplies(emptyMap(), {}, {})
    }
}

@Composable
private fun WatchActionButtons(actions: WatchActions) {
    Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        val liked = actions.rating == VideoRating.LIKE
        val disliked = actions.rating == VideoRating.DISLIKE
        TextButton(onClick = actions.onToggleLike) {
            Icon(
                imageVector = if (liked) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                contentDescription = stringResource(R.string.like),
                tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(if (liked) R.string.liked else R.string.like),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        TextButton(onClick = actions.onToggleDislike, modifier = Modifier.padding(start = 8.dp)) {
            Icon(
                imageVector = if (disliked) Icons.Filled.ThumbDown else Icons.Outlined.ThumbDown,
                contentDescription = stringResource(R.string.dislike),
                tint = if (disliked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val saved = actions.inWatchLater
        TextButton(onClick = actions.onToggleWatchLater, modifier = Modifier.padding(start = 8.dp)) {
            Icon(
                imageVector = if (saved) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
                contentDescription = stringResource(R.string.watch_later_save),
                tint = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PlayerNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Collapsible description / show-notes block. Starts clamped to a few lines
 * with a "Show more" toggle, so a long description doesn't push the comments
 * off-screen. Chapter timestamps (e.g. `1:23`) are tappable and seek playback.
 */
@Composable
private fun DescriptionSection(description: String, onSeekTo: (Long) -> Unit) {
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(description, linkColor) { description.withTimestampLinks(linkColor, onSeekTo) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.description_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = annotated,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(if (expanded) R.string.show_less else R.string.show_more),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { expanded = !expanded },
        )
    }
}

/** Artwork for an audio item (a podcast), via the same [MediaThumbnail] every list uses. */
@Composable
private fun ArtworkStage(state: PlaybackState) {
    Box(contentAlignment = Alignment.Center) {
        MediaThumbnail(
            url = HttpUrl.parse(state.artworkUrl.orEmpty()),
            contentDescription = state.title,
            modifier = Modifier
                .widthIn(max = ARTWORK_MAX_WIDTH)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
        // Spinner over the artwork while audio is buffering, mirroring the video stage.
        if (state.isBuffering) CircularProgressIndicator()
    }
}

/** Stops the screen dimming while a video is actually playing. */
@Composable
private fun KeepScreenOnWhilePlayingVideo(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
internal fun TransportControls(
    state: PlaybackState,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onSeekBackward, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.Replay10,
                contentDescription = stringResource(R.string.seek_back),
                modifier = Modifier.size(36.dp),
            )
        }
        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .size(72.dp),
        ) {
            val icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow
            val desc = if (state.isPlaying) R.string.pause else R.string.play
            Icon(icon, contentDescription = stringResource(desc), modifier = Modifier.size(40.dp))
        }
        IconButton(onClick = onSeekForward, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.Forward30,
                contentDescription = stringResource(R.string.seek_forward),
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

/** "m:ss", or "h:mm:ss" once past an hour — used for positions and durations alike. */
internal fun formatTime(millis: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(millis)
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = totalSeconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val seconds = totalSeconds % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Only covers READY landing before the track list; the gap itself is handled by isBuffering. */
private const val NO_VIDEO_GRACE_MS = 1_000L

/** Side margin for everything except the video, which is full-bleed. */
private val CONTENT_PADDING = 24.dp

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
internal const val DEFAULT_VIDEO_ASPECT_RATIO = 16f / 9f
private val ARTWORK_MAX_WIDTH = 320.dp
private const val DESCRIPTION_COLLAPSED_LINES = 4

/** Enough to read as a group without becoming a slab that competes with the artwork. */
internal const val CONTROL_SURFACE_ALPHA = 0.4f
