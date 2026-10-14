package com.dewijones92.totum.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.stats.Bar
import java.time.DayOfWeek
import java.time.format.TextStyle

@Composable
internal fun BarChart(bars: List<Bar>, byHours: Boolean, onBar: (Bar) -> Unit) {
    val values = bars.map { if (byHours) it.heardMs.toFloat() else it.plays.toFloat() }
    val top = values.maxOrNull()?.takeIf { it > 0 } ?: 1f
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(horizontal = 16.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
                .testTag(STATS_CHART_TAG)
                .pointerInput(bars) {
                    detectTapGestures { at ->
                        val index = (at.x / (size.width / bars.size.coerceAtLeast(1))).toInt()
                        bars.getOrNull(index)?.let(onBar)
                    }
                },
        ) {
            val slot = size.width / bars.size.coerceAtLeast(1)
            val gap = slot * BAR_GAP
            values.forEachIndexed { index, value ->
                val left = index * slot + gap / 2
                val width = slot - gap
                drawRoundRect(track, Offset(left, 0f), Size(width, size.height), CornerRadius(width * BAR_ROUNDING))
                val height = size.height * value / top
                drawRoundRect(
                    color,
                    Offset(left, size.height - height),
                    Size(width, height),
                    CornerRadius(width * BAR_ROUNDING)
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            val every = (bars.size / MAX_LABELS).coerceAtLeast(1)
            bars.forEachIndexed { index, bar ->
                Text(
                    if (index % every == 0) bar.label else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun Heatmap(grid: List<List<Long>>) {
    val top = grid.maxOfOrNull { row -> row.maxOrNull() ?: 0 }?.takeIf { it > 0 } ?: 1
    val color = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
        grid.forEachIndexed { day, hours ->
            Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                Text(
                    DayOfWeek.entries[day].getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.width(DAY_LABEL_WIDTH),
                )
                hours.forEach { ms ->
                    val share = ms.toFloat() / top
                    Box(
                        Modifier
                            .size(CELL_SIZE)
                            .background(
                                if (ms == 0L) empty else color.copy(alpha = MIN_ALPHA + (1 - MIN_ALPHA) * share),
                                RoundedCornerShape(2.dp),
                            ),
                    )
                }
            }
        }
    }
}

internal const val STATS_CHART_TAG = "stats-chart"
private val CHART_HEIGHT = 140.dp
private val CELL_SIZE = 11.dp
private val CELL_GAP = 2.dp
private val DAY_LABEL_WIDTH = 14.dp
private const val BAR_GAP = 0.25f
private const val BAR_ROUNDING = 0.25f
private const val MAX_LABELS = 8
private const val MIN_ALPHA = 0.2f
