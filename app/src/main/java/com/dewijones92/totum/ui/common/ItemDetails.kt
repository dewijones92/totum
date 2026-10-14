package com.dewijones92.totum.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun itemDetails(item: MediaItem, zone: ZoneId = ZoneId.systemDefault()): List<String> {
    val music = item.contentKind == MediaContentKind.MUSIC
    val published = item.publishedAt?.atZone(zone)?.format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.UK))
    return listOfNotNull(
        item.author?.let { "${if (music) FactEmoji.ARTIST else FactEmoji.CHANNEL} $it" },
        item.publisher?.let { "${FactEmoji.PUBLISHER} $it" },
        item.album?.title?.let { "${FactEmoji.ALBUM} $it" },
        (published ?: item.publishedText)?.let { "${FactEmoji.PUBLISHED} $it" },
        item.duration?.let { "⏱️ ${formatLength(it.inWholeSeconds)}" },
        item.viewsText?.let { "${FactEmoji.VIEWS} $it" },
        "🎞️ ${kindName(item.contentKind)}" + if (item.membersOnly) " · 🔒 members only" else "",
        item.chapters.takeIf { it.isNotEmpty() }?.let { "📑 ${it.size} chapters" },
        (item.mediaUrl ?: item.sourceUrl)?.let { "🔗 ${it.value}" },
        "🆔 ${item.id.value}",
        item.description?.trim()?.takeIf { it.isNotEmpty() }?.let { "📝 $it" },
    )
}

private fun kindName(kind: MediaContentKind) = when (kind) {
    MediaContentKind.STANDARD -> "Video or episode"
    MediaContentKind.LIVE -> "Live"
    MediaContentKind.SHORT -> "Short"
    MediaContentKind.MUSIC -> "Song"
}

private fun formatLength(seconds: Long): String {
    val h = seconds / SECONDS_PER_HOUR
    val m = seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
    val s = seconds % SECONDS_PER_MINUTE
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

@Composable
internal fun DetailsSection(item: MediaItem) {
    var open by remember(item.id) { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(if (open) R.string.details_hide else R.string.details_show)) },
        leadingContent = { Icon(Icons.Outlined.Info, contentDescription = null) },
        modifier = Modifier.testTag(DETAILS_TAG).clickable {
            open = !open
            if (open) Diag.log("details", "details opened for ${item.id.value}")
        },
    )
    if (open) {
        Column(Modifier.padding(horizontal = 24.dp).testTag(DETAILS_LIST_TAG)) {
            itemDetails(item).forEach { line ->
                Text(line, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

const val DETAILS_TAG = "item-details"
const val DETAILS_LIST_TAG = "item-details-list"
private const val SECONDS_PER_HOUR = 3_600
private const val SECONDS_PER_MINUTE = 60
