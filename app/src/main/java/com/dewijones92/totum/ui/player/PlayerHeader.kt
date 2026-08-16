package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.SleepTimerState
import com.dewijones92.totum.ui.cast.CastButton
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
 * Who made it, what it is called, and the facts under it — left-aligned, in that order.
 *
 * **Left-aligned and channel-first**, where this used to be three centred lines. Centred text has no
 * common edge for the eye to run down, and a long title centred over a short channel name reads as
 * two unrelated things; every player worth copying puts the source above the title, flush left. The
 * badge, overflow menu and Cast button move onto the same row as a trailing cluster instead of
 * stacking underneath, which is three fewer vertical steps before the transport.
 *
 * Nothing is lost — the badge, the menu and Cast are all still here, and
 * `PlayerKeepsEveryControlTest` is what says so.
 */
@Composable
internal fun TitleBlock(state: PlaybackState, onMore: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            state.artist?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            ViewsAndDate(state)
        }
        // The trailing cluster: what this is, the same long-press menu every row has, and Cast.
        // Sharing that sheet is what guarantees the player never offers less than a row does.
        PillarBadge(state.kind)
        onMore?.let {
            IconButton(onClick = it) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.queue_menu))
            }
        }
        // Self-hides when no Cast device is around.
        CastButton()
    }
}

@Composable
internal fun SecondaryControls(
    state: PlaybackState,
    controlsOverlaid: Boolean,
    quality: QualityControl,
    sleepTimer: SleepTimerState,
    toggles: PlaybackToggles,
    onStartSleep: (Duration) -> Unit,
    onStopSleepAfterItem: () -> Unit,
    onCancelSleep: () -> Unit,
    onSetSpeed: (Float) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = CONTROL_SURFACE_ALPHA),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // Wrapping, not scrolling: every control stays visible and reachable at any text size,
        // where a horizontal scroller would hide some of them off the right-hand edge with nothing
        // to say they were there.
        FlowRow(
            verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(4.dp),
        ) {
            SleepTimerControl(sleepTimer, onStartSleep, onStopSleepAfterItem, onCancelSleep)
            PlaybackTogglesRow(skipSilence = state.skipSilence, toggles = toggles)
            // Speed is already on the video overlay when there is a video; offering it twice would
            // be two controls for one setting.
            if (!controlsOverlaid) SpeedPicker(state.speed, onSetSpeed)
            BoostPicker(state.volumeBoost, toggles.onSetVolumeBoost)
            ListenWatchToggle(quality, state.hasVideo)
        }
    }
}

/**
 * The channel's facts on the video page — views and date, **one per line**.
 *
 * Built by the same function the rows use, so the page cannot drift from the row that led to it,
 * and laid out the same way for the same reason: Dewi asked for one fact per line in lists *and*
 * "also visible within the video page itself" (2026-08-15).
 *
 * The author is omitted rather than repeated: it is the line directly above this one.
 */
@Composable
internal fun ViewsAndDate(state: PlaybackState) {
    val facts = mediaFacts(
        author = null,
        dateText = mediaDateText(state.publishedText, state.publishedAt),
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
