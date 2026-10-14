package com.dewijones92.totum.exsurge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.theme.LocalLaurel
import kotlinx.coroutines.delay
import java.time.Instant

@Composable
fun ExsurgeStatusCard(
    view: ExsurgeView,
    onAction: (BannerAction) -> Unit,
    modifier: Modifier = Modifier,
    ticking: Boolean = true,
) {
    val now by produceState(view.at, ticking) {
        while (ticking) {
            value = Instant.now()
            delay(TICK_MS)
        }
    }
    val live = if (now.isAfter(view.at)) view.copy(at = now) else view
    val (title, text, _) = BannerText(LocalContext.current).describe(live)
    val ring = ringOf(live.banner, live.at)
    Card(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp).testTag("exsurge-status"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SurgiusRing(live.mood, ring.fraction, Modifier.testTag("exsurge-status-progress"))
            Countdown(ring, live)
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("exsurge-status-title"),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("exsurge-status-detail"),
            )
            RankBadge(live.stats)
            Actions(live.actions, onAction)
        }
    }
}

@Composable
private fun SurgiusRing(mood: Mood, fraction: Float, modifier: Modifier = Modifier) {
    val laurel = LocalLaurel.current
    val track = laurel.container
    Box(modifier.size(RING_SIZE), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = RING_STROKE.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, FULL_TURN, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                laurel.accent,
                START_AT_TOP,
                FULL_TURN * fraction,
                false,
                Offset(inset, inset),
                arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        SurgiusFace(mood, stringResource(R.string.exsurge_surgius), Modifier.size(FACE_SIZE))
    }
}

@Composable
private fun Countdown(ring: RingReading, view: ExsurgeView) {
    val caption = when (ring.kind) {
        RingKind.RISE_IN -> R.string.exsurge_ring_rise_in
        RingKind.BREAK_LEFT -> R.string.exsurge_ring_break_left
        RingKind.SNOOZED -> R.string.exsurge_ring_snoozed
        RingKind.PAUSED -> R.string.exsurge_ring_paused
        RingKind.ASLEEP -> R.string.exsurge_ring_asleep
        RingKind.STEPS -> R.string.exsurge_ring_steps
        RingKind.SUMMONED -> R.string.exsurge_ring_summoned
        RingKind.OFF -> R.string.exsurge_ring_off
    }
    val big = ring.until?.let { countdownText(it, view.at) } ?: (view.banner as? BannerLine.Rising)?.let {
        "${it.steps}/${it.needed}"
    }
    big?.let {
        Text(
            it,
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Black,
            modifier = Modifier.testTag("exsurge-countdown"),
        )
    }
    Text(
        stringResource(caption),
        style = if (big == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun RankBadge(stats: ExsurgeStats) {
    val laurel = LocalLaurel.current
    Text(
        stringResource(R.string.exsurge_rank_badge, stats.rank.latin, stats.laurels),
        style = MaterialTheme.typography.labelLarge,
        color = laurel.onContainer,
        modifier = Modifier
            .background(laurel.container, RoundedCornerShape(BADGE_CORNER))
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("exsurge-rank"),
    )
}

@Composable
private fun Actions(actions: List<BannerAction>, onAction: (BannerAction) -> Unit) {
    if (actions.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth(),
    ) {
        actions.forEachIndexed { index, action ->
            val tag = Modifier.testTag("exsurge-action-${action.name.lowercase()}")
            val label: @Composable () -> Unit = { Text(stringResource(action.label)) }
            if (index == 0) {
                Button(
                    { onAction(action) },
                    tag
                ) { label() }
            } else {
                FilledTonalButton({ onAction(action) }, tag) { label() }
            }
        }
    }
}

private val RING_SIZE: Dp = 200.dp
private val FACE_SIZE: Dp = 150.dp
private val RING_STROKE: Dp = 10.dp
private val BADGE_CORNER: Dp = 8.dp
private const val FULL_TURN = 360f
private const val START_AT_TOP = -90f
private const val TICK_MS = 1_000L
