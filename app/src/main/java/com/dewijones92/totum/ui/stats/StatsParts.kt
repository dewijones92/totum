package com.dewijones92.totum.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.Period
import com.dewijones92.totum.stats.TopKind

@Composable
internal fun StatCard(title: Int, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun CountLine(label: Int, count: Int) = StatLine(stringResource(label), count.toString())

@Composable
internal fun StatLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun SectionTitle(title: Int) = Text(
    stringResource(title),
    style = MaterialTheme.typography.titleLarge,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
)

@Composable
internal fun Hint(text: Int) = Text(
    stringResource(text),
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
)

internal fun heard(ms: Long): String {
    val minutes = ms / MS_PER_MINUTE
    return when {
        minutes >= MINUTES_PER_HOUR -> "%dh %02dm".format(minutes / MINUTES_PER_HOUR, minutes % MINUTES_PER_HOUR)
        minutes > 0 -> "${minutes}m"
        else -> "${ms / MS_PER_SECOND}s"
    }
}

internal fun megabytes(bytes: Long): String = "%.1f MB".format(bytes / BYTES_PER_MB)

internal val Period.label: Int
    get() = when (this) {
        Period.DAY -> R.string.stats_period_day
        Period.WEEK -> R.string.stats_period_week
        Period.MONTH -> R.string.stats_period_month
        Period.YEAR -> R.string.stats_period_year
        Period.ALL -> R.string.stats_period_all
    }

internal val ListenKind.label: Int
    get() = when (this) {
        ListenKind.MUSIC -> R.string.stats_kind_music
        ListenKind.PODCAST -> R.string.stats_kind_podcast
        ListenKind.VIDEO -> R.string.stats_kind_video
    }

internal val TopKind.label: Int
    get() = when (this) {
        TopKind.ARTISTS -> R.string.stats_top_artists
        TopKind.SONGS -> R.string.stats_top_songs
        TopKind.ALBUMS -> R.string.stats_top_albums
        TopKind.SHOWS -> R.string.stats_top_shows
        TopKind.CHANNELS -> R.string.stats_top_channels
    }

private const val MS_PER_SECOND = 1_000L
private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val BYTES_PER_MB = 1_048_576.0
