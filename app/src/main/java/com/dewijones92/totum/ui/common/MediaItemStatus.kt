package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.isPermanent

/**
 * The one status line every row shows: which pillar the item is, whether it's held
 * offline, and whether it's been played. One component, so the three signals read
 * consistently in every list — feeds, search, queue, history, playlists, Library.
 *
 * Deliberately quiet for the default case: an unplayed, streaming item shows only its
 * pillar glyph. State that shouts on every row stops carrying information.
 */
@Composable
internal fun MediaItemStatus(
    pillar: MediaKind,
    playState: PlayState,
    downloadState: DownloadState,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        StatusIcon(
            icon = if (pillar == MediaKind.PODCAST) Icons.Filled.Podcasts else Icons.Filled.SmartDisplay,
            descriptionRes = if (pillar == MediaKind.PODCAST) R.string.pillar_podcast else R.string.pillar_video,
        )
        // Distinct from the trailing download button, which is the *action*: this says
        // "you have this offline" without also meaning "tap to delete it". A down-arrow
        // rather than another check, so it can't be read as the played tick.
        //
        // Audio-only and full downloads are shown DIFFERENTLY (Dewi, 2026-07-25, reversing
        // the earlier "a download is a download"): the queue fetches audio automatically, so
        // most offline items are audio — and "I have this offline" meaning two different
        // things with one glyph is exactly the ambiguity worth removing.
        if (downloadState is DownloadState.Downloaded) {
            if (downloadState.audioOnly) {
                StatusIcon(Icons.Filled.Headphones, R.string.status_offline_audio)
            } else {
                StatusIcon(Icons.Filled.DownloadForOffline, R.string.status_offline_video)
            }
        }
        // The state that was completely invisible: a row being fetched right now looked exactly
        // like one nobody had touched. Dewi, 2026-08-02: "its not clear from gui what is
        // downloading atm". Percentage when the size is known, plain "Downloading…" when it is
        // not — a server that sends no Content-Length must not produce a stuck "0%".
        if (downloadState is DownloadState.Downloading) {
            val percent = downloadState.fraction?.let { (it * PERCENT).toInt() }
            Text(
                text = percent?.let { stringResource(R.string.status_downloading_percent, it) }
                    ?: stringResource(R.string.status_downloading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // In WORDS, not a glyph. A permanently-failed download is the one state a person has to
        // understand rather than glance at — it is why the queue summary says "4 can't be
        // downloaded", and the row has to say WHICH four. Retryable failures deliberately show
        // nothing: the app is still trying, so there is nothing to tell anyone yet.
        if (downloadState is DownloadState.Failed && downloadState.isPermanent) {
            Text(
                text = stringResource(R.string.status_online_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (playState.isPlayed) {
            StatusIcon(Icons.Filled.Check, R.string.status_played)
        }
    }
}

@Composable
private fun StatusIcon(icon: ImageVector, descriptionRes: Int) {
    Icon(
        imageVector = icon,
        contentDescription = stringResource(descriptionRes),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(STATUS_ICON_SIZE),
    )
}

/**
 * A thin sliver under the thumbnail showing how far in an item is — far more
 * informative at a glance than a "part-way" label, and the reason progress isn't in
 * the icon row. Nothing is drawn for unplayed or played items, or while the duration
 * is unknown, so the sliver only ever means "you are here".
 */
@Composable
internal fun PlayProgressSliver(playState: PlayState, modifier: Modifier = Modifier) {
    val fraction = (playState as? PlayState.InProgress)?.fraction ?: return
    Spacer(Modifier.height(2.dp))
    LinearProgressIndicator(
        progress = { fraction },
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        drawStopIndicator = {},
        modifier = modifier.height(SLIVER_HEIGHT),
    )
}

/**
 * Dims a played item's title, so a finished row recedes without disappearing.
 *
 * **0.65, up from 0.55 (2026-09-21).** At 0.55 a played title measured **3.79:1** against its own
 * background in the light theme — below WCAG AA's 4.5:1 for body text, and this is 14sp, which is
 * not "large text" by any reading. That was already true before the wash existed (3.89:1 dimmed
 * alone), so it is a pre-existing defect rather than one the colour introduced; the wash costs
 * 0.10 of it in the light theme and 0.91 in the dark, which still leaves dark passing.
 *
 * Raising it is the right fix rather than a patch, because the row now recedes by COLOUR: the
 * dimming no longer has to carry "finished" on its own, so it can afford to be legible. The
 * threshold is 0.606; 0.65 is the first 0.05 step past it and leaves real margin (5.18 light,
 * 5.87 dark) rather than sitting on the line.
 */
@Composable
internal fun playedTitleAlpha(playState: PlayState): Float =
    if (playState.isPlayed) PLAYED_TITLE_ALPHA else 1f

/**
 * The ONE background a row wears: its pillar's wash, with at most one state wash over it — **played
 * wins** over unread.
 *
 * Two draw modifiers both painting a background do not choose between themselves, they composite:
 * the Notifications tab passed an unread wash through `modifier` and the row painted the played wash
 * on top of it, making a third colour that read as neither "new" nor "finished". Played wins because
 * it is the later fact about the item — an episode you have finished is not news any more. Unread
 * is the stronger `primary` rather than `primaryContainer`, because the video wash under it is
 * `primaryContainer` already and more of the same colour does not read as a different state.
 */
@Composable
internal fun rowTint(pillar: MediaKind, playState: PlayState, unread: Boolean = false): Color {
    val state = when {
        playState.isPlayed -> playedRowTint(playState)
        unread -> MaterialTheme.colorScheme.primary.copy(alpha = UNREAD_TINT_ALPHA)
        else -> Color.Transparent
    }
    return state.compositeOver(pillarRowTint(pillar))
}

/**
 * The wash a finished row wears — cyan, faintly — and [Color.Transparent] for every other row.
 *
 * Dewi asked for "a tinge of a colour" on played items (2026-09-21) and chose cyan from the app's
 * own palette. Cyan rather than the tangerine hero because tangerine already means *active* — it
 * is the play button, the now-playing equaliser and every primary action — so a played row painted
 * with it would say the opposite of what it is. Neither is it green: this brand has three hues and
 * "done" is not worth a fourth.
 *
 * Kept deliberately faint. It sits UNDER the text on every list, so it has to stay clear of both
 * `onSurface` and `onSurfaceVariant` at any size; a wash you have to be told about is doing its
 * job, where one you can read a title through is not.
 *
 * Only PLAYED, and only this state: part-way items already carry the progress sliver and the queue
 * labels the row it is on, so tinting those too would leave nothing untinted to compare against.
 */
@Composable
internal fun playedRowTint(playState: PlayState): Color {
    if (!playState.isPlayed) return Color.Transparent
    val scheme = MaterialTheme.colorScheme
    // A dark surface needs MORE alpha to read as a hue at all. Verified by looking, not reasoned:
    // 8% was plainly cyan on Sand99 and came out as a lighter grey band on Sand10 — measurably
    // bluer, and not blue to the eye, which is not what "a tinge of a colour" means. Note the TONE
    // differs too (`secondary` is Cyan40 in the light theme and Cyan80 in the dark), so this is not
    // "the same cyan behaving differently"; it is two washes, each chosen for its own surface.
    // Keyed off the surface's luminance rather than a dark-theme flag so a scheme that is neither of
    // ours — dynamic colour, if it is ever switched on — still gets a decision rather than a default.
    val alpha = if (scheme.surface.luminance() < DARK_SURFACE_LUMINANCE) {
        PLAYED_ROW_TINT_ALPHA_ON_DARK
    } else {
        PLAYED_ROW_TINT_ALPHA
    }
    return scheme.secondary.copy(alpha = alpha)
}

@Composable
internal fun pillarRowTint(pillar: MediaKind): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < DARK_SURFACE_LUMINANCE
    return when (pillar) {
        MediaKind.VIDEO -> scheme.primaryContainer.copy(
            alpha = if (dark) VIDEO_TINT_ALPHA_ON_DARK else VIDEO_TINT_ALPHA
        )
        MediaKind.PODCAST ->
            scheme.tertiaryContainer.copy(alpha = if (dark) PODCAST_TINT_ALPHA_ON_DARK else PODCAST_TINT_ALPHA)
    }
}

@Composable
internal fun selectedRowTint(): Color = MaterialTheme.colorScheme.primary.copy(alpha = SELECTED_TINT_ALPHA)

private const val SELECTED_TINT_ALPHA = 0.28f
private const val VIDEO_TINT_ALPHA = 0.14f
private const val VIDEO_TINT_ALPHA_ON_DARK = 0.30f
private const val PODCAST_TINT_ALPHA = 0.18f
private const val PODCAST_TINT_ALPHA_ON_DARK = 0.30f

private val STATUS_ICON_SIZE = 14.dp
private val SLIVER_HEIGHT = 3.dp
private const val PLAYED_TITLE_ALPHA = 0.65f
private const val PLAYED_ROW_TINT_ALPHA = 0.08f

/**
 * The Notifications tab's "arrived since you last looked" wash, moved here so the two cannot stack.
 * Unchanged at 0.35 — it is a container colour rather than a hue, so it can afford to be stronger
 * than the played wash.
 */
private const val UNREAD_TINT_ALPHA = 0.15f
private const val PLAYED_ROW_TINT_ALPHA_ON_DARK = 0.14f
private const val DARK_SURFACE_LUMINANCE = 0.5f

/** Padding that keeps the status row visually attached to the text above it. */
internal val StatusRowSpacing = Modifier.padding(top = 3.dp)

/** Fractions are 0..1; people read percentages. */
private const val PERCENT = 100
