package com.dewijones92.totum.ui.shorts

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.dewijones92.totum.R
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.ReelStart
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.ui.common.ItemActionSheet
import com.dewijones92.totum.video.VideoPlaybackLauncher

/**
 * A full-screen vertical Shorts reel: swipe up/down between shorts, each playing
 * through the one shared player (resolved just-in-time when it settles), tap to
 * play/pause. A finished short rolls on to the next. Uses the same playback
 * session as everything else, so closing the reel keeps it playing in the mini
 * player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun ShortsReelScreen(
    container: AppContainer,
    /**
     * The Shorts to page through AND which one to open on — together, because they are one answer.
     * Tapping a Short in the feed opens the reel THERE, with the ones above it still behind you,
     * rather than the reel pretending your feed started at the item you touched.
     */
    reel: ReelStart,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shorts = reel.shorts
    BackHandler(onBack = onBack)
    if (shorts.isEmpty()) {
        ShortsEmpty(onBack, modifier)
        return
    }
    val playback = container.playbackController
    val launcher = container.videoPlaybackLauncher
    val queue = container.playbackQueue
    val audioMode = container.appPreferences.settings.collectAsStateWithLifecycle().value.playbackMode ==
        PlaybackMode.AUDIO
    val context = LocalContext.current
    val kept = stringResource(R.string.watching_this_one)
    val shortsRun = stringResource(R.string.shorts_title)
    // Say so once per reel, so forcing video here never looks like the mode changed.
    LaunchedEffect(audioMode) {
        if (audioMode) Toast.makeText(context, kept, Toast.LENGTH_SHORT).show()
    }
    val state by playback.state.collectAsStateWithLifecycle()
    val openOn = reel.index.coerceIn(0, (shorts.size - 1).coerceAtLeast(0))
    val pager = rememberPagerState(initialPage = openOn) { shorts.size }

    ReelQueueBinding(shorts, state, pager, queue, launcher, runTitle = shortsRun)

    Surface(color = Color.Black, modifier = modifier.fillMaxSize()) {
        VerticalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            ShortPage(
                short = shorts[page],
                player = playback.player,
                isCurrent = page == pager.currentPage,
                state = state,
                onTogglePlayPause = playback::togglePlayPause,
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }
    }
}

/**
 * Keeps the reel and the queue agreeing, in both directions.
 *
 * The run is enqueued once so that a short can be advanced past by the app-scoped advancer
 * exactly like any other item — including with the screen off. Queueing one short at a time
 * (the old behaviour) meant the next one did not exist yet at the moment the current one
 * ended, which is why the reel needed its own advance and a suppression flag. Neither
 * survives: shorts are treated equally.
 */
@Composable
private fun ReelQueueBinding(
    shorts: List<MediaItem>,
    state: PlaybackState?,
    pager: PagerState,
    queue: PlaybackQueue,
    launcher: VideoPlaybackLauncher,
    runTitle: String,
) {
    LaunchedEffect(shorts) {
        queue.playAll(
            shorts.mapNotNull { short ->
                short.mediaUrl?.let { PlayableItem(short, PlayHandle.Video(it)) }
            },
            QueueGroup(id = "shorts:${shorts.firstOrNull()?.id?.value.orEmpty()}", title = runTitle),
        )
    }

    // A Shorts reel is inherently visual: show the picture even in audio mode, for these
    // items only. The mode itself is left alone (and said so, by the toast).
    LaunchedEffect(state?.itemId) {
        if (state?.hasVideo == false) launcher.watch()
    }

    // Swiping picks a short: move the queue cursor rather than playing outside the queue, so
    // the run stays intact and advancing still knows what follows.
    LaunchedEffect(pager.settledPage) {
        val wanted = shorts.getOrNull(pager.settledPage) ?: return@LaunchedEffect
        if (state?.itemId == wanted.id) return@LaunchedEffect
        queue.state.value.entries
            .indexOfFirst { it.item.item.id == wanted.id }
            .takeIf { it >= 0 }
            ?.let(queue::jumpTo)
    }

    // ...and the reverse: when something else advances the queue — a short ending in a pocket
    // — the pager follows what is playing rather than the two disagreeing.
    LaunchedEffect(state?.itemId) {
        val playing = state?.itemId ?: return@LaunchedEffect
        shorts.indexOfFirst { it.id == playing }
            .takeIf { it >= 0 && it != pager.currentPage }
            ?.let { pager.animateScrollToPage(it) }
    }
}

/** One reel page: the short's video (only bound on the current page), a buffering spinner, and its title. */
@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ShortPage(
    short: MediaItem,
    player: Player?,
    isCurrent: Boolean,
    state: PlaybackState?,
    onTogglePlayPause: () -> Unit,
) {
    var showSheet by remember { mutableStateOf(false) }
    if (showSheet) ItemActionSheet(short, onDismiss = { showSheet = false })
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTogglePlayPause,
                // A short is a video like any other, so it offers the same actions as a
                // row does. This surface had none at all, purely because it is not a list.
                onLongClick = { showSheet = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isCurrent && player != null && state?.hasVideo == true) {
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (isCurrent && state?.isBuffering == true) {
            CircularProgressIndicator(color = Color.White)
        }
        Text(
            text = short.title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun ShortsEmpty(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    Surface(color = Color.Black, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.shorts_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
            )
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(8.dp),
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close), tint = Color.White)
        }
    }
}
