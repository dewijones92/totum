package com.dewijones92.totum.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.ui.cast.CastButton
import com.dewijones92.totum.ui.cast.ScreenButton
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.PillarBadge
import com.dewijones92.totum.ui.common.mediaDateText
import com.dewijones92.totum.ui.common.mediaFacts
import kotlin.time.Duration

/**
 * The header and the control strip — the two pieces the 2026-08-07 redesign is really about.
 *
 * Their own file because `FullPlayer` was already at the per-file limit, and because these are where
 * the screen stopped reading as a settings page: a left-aligned header with the source above the
 * title, and one strip where five stacked full-width control rows used to be.
 */

/**
 * Who made it, what it is called, and the facts under it — left-aligned, in that order, across the whole
 * width: the menu and Cast live in the top bar ([PlayerTopActions]), so the title is never squeezed beside
 * them (Dewi's report from 0.1.643: a five-line title in half the width).
 */
@Composable
internal fun TitleBlock(state: PlaybackState) {
    val links = LocalPlayerLinks.current
    Column(modifier = Modifier.fillMaxWidth()) {
        state.artist?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.linkTo(links.onArtist, PLAYER_ARTIST_TAG),
            )
        }
        Text(
            text = state.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
        links.album?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).linkTo(links.onAlbum, PLAYER_ALBUM_TAG),
            )
        }
        PillarBadge(
            state.kind,
            music = state.contentKind == MediaContentKind.MUSIC,
            modifier = Modifier.padding(top = 6.dp),
        )
        ViewsAndDate(state)
    }
}

/** Cast and the same long-press menu every row has, in the player's top bar; [onDark] over a video. */
@Composable
internal fun PlayerTopActions(onMore: (() -> Unit)?, onDark: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScreenButton(onDark = onDark)
        CastButton(modifier = Modifier.size(CAST_SIZE), onDark = onDark)
        onMore?.let {
            IconButton(onClick = it) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.queue_menu))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SecondaryControls(toggles: PlaybackToggles) {
    val tiles = buildList<Pair<String, @Composable (Modifier) -> Unit>> {
        add("autoplay" to { m -> AutoPlayNextTile(toggles.autoPlayNext, toggles.onSetAutoPlayNext, m) })
        add("repeat" to { m -> RepeatTile(toggles.repeatMode, toggles.onSetRepeatMode, m) })
        add("fast-start" to { m -> FastStartTile(toggles.sabrPlayback, toggles.onSetSabrPlayback, m) })
        toggles.onKidLock?.let { lock -> add("kid-lock" to { m -> KidLockTile(lock, m) }) }
    }
    // Wrapping, not scrolling: every control stays visible and reachable at any text size,
    // where a horizontal scroller would hide some of them off the right-hand edge with nothing
    // to say they were there.
    FlowRow(
        maxItemsInEachRow = TILES_PER_ROW,
        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        modifier = Modifier.fillMaxWidth(),
    ) {
        tiles.forEach { (name, tile) -> key(name) { tile(Modifier.weight(1f).fillMaxRowHeight()) } }
        if (tiles.size % TILES_PER_ROW != 0) Spacer(Modifier.weight(1f))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerQuickStrip(
    state: PlaybackState,
    quality: QualityControl,
    sleepTimer: State<SleepTimerState>,
    toggles: PlaybackToggles,
    onStartSleep: (Duration) -> Unit,
    onStopSleepAfterItem: () -> Unit,
    onCancelSleep: () -> Unit,
    onSetSpeed: (Float) -> Unit,
) {
    CompositionLocalProvider(LocalCompactTiles provides true) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(STRIP_GAP),
            verticalArrangement = Arrangement.spacedBy(STRIP_GAP),
            modifier = Modifier.fillMaxWidth().testTag(PLAYER_QUICK_STRIP_TAG),
        ) {
            ListenWatchToggle(quality, state.hasVideo, hasPictureTrack = state.hasPictureTrack)
            SpeedTile(state.speed, onSetSpeed)
            BoostTile(state.volumeBoost, toggles.onSetVolumeBoost)
            SkipSilenceTile(state.skipSilence, toggles.onSetSkipSilence)
            SleepTimerControl(sleepTimer.value, onStartSleep, onStopSleepAfterItem, onCancelSleep)
        }
    }
}

private fun Modifier.linkTo(onClick: (() -> Unit)?, tag: String): Modifier =
    testTag(tag).then(onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier)

internal const val PLAYER_ARTIST_TAG = "player-artist"
internal const val PLAYER_ALBUM_TAG = "player-album"

private const val TILES_PER_ROW = 2
private val STRIP_GAP = 8.dp
internal const val PLAYER_QUICK_STRIP_TAG = "player-quick-strip"
private val CAST_SIZE = 48.dp
private val TILE_GAP = 10.dp

/**
 * The channel's facts on the video page — views and date, **one per line**.
 *
 * Built by the same function the rows use, so the page cannot drift from the row that led to it,
 * and laid out the same way for the same reason: Dewi asked for one fact per line in lists *and*
 * "also visible within the video page itself" (2026-08-15).
 *
 * The author is omitted rather than repeated: it is the line directly above this one. Its
 * publisher is not — that name appears nowhere else on the page.
 */
@Composable
internal fun ViewsAndDate(state: PlaybackState) {
    val facts = mediaFacts(
        author = null,
        // The publisher IS shown here, unlike the author: the line above is the show's name, and
        // the network behind it is a second fact rather than a repeat of that one.
        publisher = state.publisher,
        dateText = mediaDateText(state.publishedText, state.publishedAt, LocalNow.current),
        viewsText = state.viewsText,
    )
    if (facts.isEmpty()) return
    // The tag stays on the CONTAINER, so a test asserting the page's metadata finds one node
    // whose text is all of it — splitting it across children would quietly break that assertion
    // into "no node" rather than into a visible failure.
    Column(modifier = Modifier.padding(top = 4.dp).testTag(PLAYER_METADATA_TAG)) {
        facts.forEach { fact ->
            Text(
                text = fact,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
