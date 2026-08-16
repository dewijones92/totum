package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
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
fun mediaItemFacts(item: MediaItem): List<String> =
    mediaFacts(item.author, mediaDateText(item.publishedText, item.publishedAt), item.viewsText)

/**
 * When a thing was published, as a list says it.
 *
 * Its own function because the video page has to say it too, and it holds a real decision: prefer
 * the source's own relative wording ("2 days ago") over a formatted absolute date, because that is
 * what YouTube gives and re-deriving "2 days ago" from a timestamp would drift from the site.
 *
 * Deliberately NOT `@Composable` — nor is [mediaFacts] any more, though both used to be. Neither
 * ever called anything composable, and the annotation was the only thing keeping the rule that
 * decides what appears under every video title out of reach of a JVM unit test.
 */
fun mediaDateText(publishedText: String?, publishedAt: Instant?): String? =
    publishedText ?: publishedAt?.let {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(it)
    }

/**
 * The one place that decides which facts appear under a title and in what order. Takes the parts
 * rather than a [MediaItem] so search hits — which aren't media items yet — read identically to
 * every other list instead of composing their own.
 *
 * Blanks are dropped rather than rendered as empty lines: a podcast episode has no view count and
 * must not leave a gap where one would be.
 */
fun mediaFacts(author: String?, dateText: String?, viewsText: String? = null): List<String> =
    listOfNotNull(author, viewsText, dateText).filter { it.isNotBlank() }

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
