package com.dewijones92.totum.ui.player

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.Chapter
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.playback.BufferAhead
import com.dewijones92.totum.playback.BufferGauge
import com.dewijones92.totum.playback.PlaybackState
import kotlinx.coroutines.delay

/**
 * The scrubber: a slider over the current position, the elapsed/total times,
 * and (for anything with skip segments) a SponsorBlock-style strip under it.
 * Used both below the artwork (audio) and overlaid on the video.
 */
@Composable
internal fun SeekBar(
    state: PlaybackState,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    bufferAhead: BufferAhead? = rememberBufferAhead(state),
) {
    val duration = state.durationMs
    var dragValue by remember(state.positionMs) { mutableStateOf<Float?>(null) }
    val position = dragValue?.toLong() ?: state.positionMs

    Column(modifier = modifier.fillMaxWidth()) {
        if (duration != null) {
            Slider(
                value = position.coerceIn(0, duration).toFloat(),
                onValueChange = { dragValue = it },
                onValueChangeFinished = {
                    dragValue?.let { onSeekTo(it.toLong()) }
                    dragValue = null
                },
                valueRange = 0f..duration.toFloat(),
            )
            SkipSegmentBar(state.skipSegments, duration)
            ChapterMarkerBar(state.chapters, duration)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatTime(position), style = MaterialTheme.typography.labelMedium)
                BufferAheadLabel(bufferAhead, Modifier.weight(1f).padding(horizontal = 8.dp))
                Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
            }
        } else {
            Text(
                text = formatTime(state.positionMs),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * A thin SponsorBlock-style strip under the scrubber marking the skip segments
 * (green), so you can see what will be auto-skipped. Inset to line up with the
 * slider's track (which is padded by the thumb radius). Nothing drawn when
 * there are no segments.
 */
@Composable
private fun SkipSegmentBar(segments: List<SkipSegment>, durationMs: Long) {
    if (segments.isEmpty() || durationMs <= 0) return
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SLIDER_THUMB_INSET)
            .height(SEGMENT_BAR_HEIGHT),
    ) {
        segments.forEach { segment ->
            val startX = fractionX(segment.start.inWholeMilliseconds, durationMs)
            val endX = fractionX(segment.end.inWholeMilliseconds, durationMs)
            drawRect(
                color = SponsorSegmentColor,
                topLeft = Offset(startX, 0f),
                size = Size((endX - startX).coerceAtLeast(1f), size.height),
            )
        }
    }
}

/**
 * The sponsor segments spelled out as time ranges (e.g. "2:10 – 3:05"), in the
 * same green as the strip, so it's clear exactly what gets auto-skipped and when.
 */
@Composable
internal fun SponsorSegments(segments: List<SkipSegment>, modifier: Modifier = Modifier) {
    if (segments.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.sponsor_segments_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        segments.sortedBy { it.start }.forEach { segment ->
            val range = "${formatTime(segment.start.inWholeMilliseconds)} – " +
                formatTime(segment.end.inWholeMilliseconds)
            Text(
                text = range,
                style = MaterialTheme.typography.labelLarge,
                color = SponsorSegmentColor,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}

/**
 * Thin vertical ticks under the scrubber at each chapter's start, so chapter
 * boundaries are visible while scrubbing. Amber, to read on both the light
 * (audio) and dark (video) backgrounds and stay distinct from the sponsor green.
 */
@Composable
private fun ChapterMarkerBar(chapters: List<Chapter>, durationMs: Long) {
    if (chapters.isEmpty() || durationMs <= 0) return
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SLIDER_THUMB_INSET)
            .height(SEGMENT_BAR_HEIGHT),
    ) {
        val tickWidth = CHAPTER_TICK_WIDTH.toPx()
        chapters.forEach { chapter ->
            val x = fractionX(chapter.start.inWholeMilliseconds, durationMs)
            drawRect(
                color = ChapterMarkerColor,
                topLeft = Offset((x - tickWidth / 2).coerceAtLeast(0f), 0f),
                size = Size(tickWidth, size.height),
            )
        }
    }
}

/** The chapters as a tappable list; tapping one seeks the player to its start. */
@Composable
internal fun ChapterList(chapters: List<Chapter>, onSeekTo: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (chapters.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.chapters_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        chapters.sortedBy { it.start }.forEach { chapter ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeekTo(chapter.start.inWholeMilliseconds) }
                    .padding(vertical = 6.dp),
            ) {
                Text(
                    text = formatTime(chapter.start.inWholeMilliseconds),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** X position on a full-width strip for [ms] into a [durationMs] track, clamped to the ends. */
private fun DrawScope.fractionX(ms: Long, durationMs: Long): Float =
    (ms.toFloat() / durationMs).coerceIn(0f, 1f) * size.width

private val SLIDER_THUMB_INSET = 10.dp
private val SEGMENT_BAR_HEIGHT = 4.dp
private val CHAPTER_TICK_WIDTH = 2.dp
private val SponsorSegmentColor = Color(0xFF2ECC71) // SponsorBlock's brand green
private val ChapterMarkerColor = Color(0xFFFFC107) // amber — visible on light and dark

/**
 * How many seconds of the file you actually hold ahead of the playhead.
 *
 * Dewi, 2026-08-02: *"lets make it clear in the gui how much of the 'future' of the file is
 * downloaded"*. Shown only once it has been LOW for a moment, because a healthy buffer is not
 * news — a permanent "312s buffered" would be noise on every well-behaved video and would stop
 * being read long before the one time it mattered.
 *
 * The direction is the point once it is low. A small buffer that is filling will be fine; one
 * that is draining will not. A LARGE buffer draining is the player's ordinary cycle between
 * loads, and showing it made the label blink under the scrubber every second (2026-09-26).
 */
@Composable
private fun BufferAheadLabel(ahead: BufferAhead?, modifier: Modifier) {
    Text(
        text = when {
            ahead == null -> ""
            ahead.falling -> stringResource(R.string.buffer_ahead_falling, ahead.seconds)
            else -> stringResource(R.string.buffer_ahead, ahead.seconds)
        },
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        color = if (ahead?.falling == true) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier,
    )
}

@Composable
internal fun rememberBufferAhead(state: PlaybackState): BufferAhead? {
    val gauge = remember { BufferGauge() }
    var wokenAt by remember { mutableLongStateOf(0L) }
    val now = maxOf(SystemClock.elapsedRealtime(), wokenAt)
    val ahead = gauge.update(state, now)
    val waitMs = gauge.waitMs(now)
    LaunchedEffect(waitMs == null, state.itemId) {
        if (waitMs != null) {
            delay(waitMs)
            wokenAt = SystemClock.elapsedRealtime()
        }
    }
    return ahead
}
