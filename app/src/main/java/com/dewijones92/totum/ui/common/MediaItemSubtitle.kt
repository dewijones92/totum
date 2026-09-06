package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PublishedAge
import java.time.Instant
import kotlin.time.Duration

/**
 * The facts shown under a media title, **one per line**, in reading order: channel, views, date.
 *
 * A list, not a joined string, and that is the whole point. They used to be one line —
 * `author · views · date` — capped at `maxLines = 1`, so on a real phone the tail was replaced by
 * an ellipsis and the view count or the date simply vanished. Dewi, 2026-08-15: *"the view count
 * and the date published on YouTube videos sometimes gets hidden, there's like a 3-dot thing. I
 * want them each to be on a separate line."*
 *
 * Returning the parts rather than a rendered line is what lets one seam serve both the row and the
 * video page, which is the other half of what he asked for. Whoever renders decides how; only this
 * decides *what* and *in what order*.
 *
 * Duration is deliberately NOT here — it rides on the thumbnail corner, where nothing can truncate
 * it and it costs no vertical space. His call when choosing this layout.
 */
fun mediaItemFacts(item: MediaItem, pillar: MediaKind, now: Instant = Instant.now()): List<String> = mediaFacts(
    author = item.author,
    dateText = mediaDateText(item.publishedText, item.publishedAt, now),
    viewsText = item.viewsText,
    // Which badge the maker gets. [pillar] is PASSED, not inferred from the URL — every caller
    // already knows it exactly (it hands the same value to `MediaItemRow` on the next line), and
    // `MediaItem.pillar` is documented as a guess for items that have no handle yet. Re-deriving
    // it here would put back the second rule that a Shorts URL once fell down the gap between.
    authorEmoji = if (pillar == MediaKind.PODCAST) FactEmoji.PODCAST else FactEmoji.CHANNEL,
)

/**
 * The emoji that labels each kind of fact, defined once so nothing can disagree about which glyph
 * means what.
 *
 * Dewi, 2026-08-15: *"can we put in emojis? Views has an eyes emoji prefix to it … I love emojis."*
 * They earn their place beyond decoration: three stacked grey lines of similar length are hard to
 * tell apart at a glance, and a leading glyph says WHICH fact a line is before you read it.
 */
object FactEmoji {
    /** The maker — a TV for a channel, a mic for a podcast, so a mixed list tells you which. */
    const val CHANNEL: String = "📺"
    const val PODCAST: String = "🎙️"

    /** The two Dewi named himself. */
    const val VIEWS: String = "👁️"
    const val PUBLISHED: String = "📅"

    /** What a downloaded copy costs on the disk — the Library's extra line. */
    const val ON_DISK: String = "💾"

    /** A queue row that cannot be played right now. */
    const val UNAVAILABLE: String = "⚠️"

    /** YouTube Music, which is its own kind of hit on the search page. */
    const val SONG: String = "🎵"
}

/**
 * When a thing was published, as a list says it — "2 hours ago", against [now].
 *
 * The instant wins over the source's wording, which reverses the earlier rule and does so on
 * purpose (Dewi, 2026-09-06: *"this should obviously increase as time goes by"*). A source's
 * "2 hours ago" is anchored to an instant when it is observed, and re-derived here every time a
 * row is drawn, so the label ages; the wording itself is only shown for rows persisted before
 * anchoring existed, which have nothing else. Both pillars read the same way — a podcast episode
 * is "3 days ago" too, not an absolute date beside a video's relative one.
 */
fun mediaDateText(publishedText: String?, publishedAt: Instant?, now: Instant = Instant.now()): String? =
    publishedAt?.let { PublishedAge.text(it, now) } ?: publishedText

/**
 * The one place that decides which facts appear under a title and in what order. Takes the parts
 * rather than a [MediaItem] so search hits — which aren't media items yet — read identically to
 * every other list instead of composing their own.
 *
 * Blanks are dropped rather than rendered as empty lines: a podcast episode has no view count and
 * must not leave a gap where one would be.
 */
fun mediaFacts(
    author: String?,
    dateText: String?,
    viewsText: String? = null,
    authorEmoji: String = FactEmoji.CHANNEL,
): List<String> = listOfNotNull(
    author.labelled(authorEmoji),
    viewsText.labelled(FactEmoji.VIEWS),
    dateText.labelled(FactEmoji.PUBLISHED),
)

/**
 * A fact with its emoji, or null when there is no fact. Applied here rather than at each `Text`, so
 * the pairing is unit-testable and every surface reads the same.
 */
private fun String?.labelled(emoji: String): String? =
    this?.takeIf { it.isNotBlank() }?.let { "$emoji $it" }

/**
 * "12:34" / "1:02:45" — how every player writes a length, and short enough to sit on a
 * thumbnail. Minutes-only ("34 min") was what the subtitle used to say, which is both
 * longer and vaguer, and rounded a 45-second Short to nothing at all.
 */
fun formatClock(duration: Duration): String {
    val total = duration.inWholeSeconds
    val hours = total / SECONDS_PER_HOUR
    val minutes = (total % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = total % SECONDS_PER_MINUTE
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** A row's duration chip: the clock, or nothing when the source never said. */
fun durationLabel(item: MediaItem): String? = item.duration?.let(::formatClock)

/** Kept for the "N min" phrasing where a full clock would be overkill (sleep timer, settings). */
@Composable
fun minutesLabel(minutes: Long): String = stringResource(R.string.duration_minutes, minutes)

private const val SECONDS_PER_MINUTE = 60
private const val SECONDS_PER_HOUR = 3600
