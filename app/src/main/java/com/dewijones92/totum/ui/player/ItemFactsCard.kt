package com.dewijones92.totum.ui.player

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.downloadStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val LocalPlayingItem = compositionLocalOf<MediaItem?> { null }

@Composable
internal fun ItemFactsCard(state: PlaybackState) {
    val item = LocalPlayingItem.current?.takeIf { it.id == state.itemId }
    val offline = downloadStateOf(state.itemId)
    val bytes by produceState<Long?>(null, offline) {
        value = (offline as? DownloadState.Downloaded)?.let {
            withContext(Dispatchers.IO) { File(it.localPath).length() }
        }
    }
    val facts = itemFacts(item, state, offline, bytes)
    if (facts.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(ITEM_FACTS_TAG),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.item_facts_title), style = MaterialTheme.typography.titleMedium)
            facts.forEach { fact ->
                val (label, value) = describe(fact)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(LABEL_WEIGHT),
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(VALUE_WEIGHT)
                    )
                }
            }
        }
    }
}

@Composable
private fun describe(fact: ItemFact): Pair<String, String> = when (fact) {
    is ItemFact.Published -> stringResource(R.string.item_fact_published) to publishedValue(fact)
    is ItemFact.Length -> stringResource(R.string.item_fact_length) to formatTime(fact.ms)
    is ItemFact.Views -> stringResource(R.string.item_fact_views) to fact.text
    is ItemFact.By -> stringResource(R.string.item_fact_by) to fact.name
    is ItemFact.Publisher -> stringResource(R.string.item_fact_publisher) to fact.name
    is ItemFact.Album -> stringResource(R.string.item_fact_album) to fact.title
    is ItemFact.Kind -> stringResource(R.string.item_fact_kind) to stringResource(kindLabel(fact))
    ItemFact.MembersOnly -> stringResource(R.string.item_fact_members) to stringResource(R.string.item_fact_yes)
    is ItemFact.Chapters -> stringResource(R.string.item_fact_chapters) to fact.count.toString()
    is ItemFact.Subtitles -> stringResource(R.string.item_fact_subtitles) to fact.languages.joinToString(", ")
    is ItemFact.Skips -> stringResource(R.string.item_fact_skips) to fact.count.toString()
    is ItemFact.Offline -> stringResource(R.string.item_fact_offline) to offlineValue(fact)
    is ItemFact.Source -> stringResource(R.string.item_fact_source) to fact.host
}

@Composable
private fun publishedValue(fact: ItemFact.Published): String {
    val at = fact.at ?: return fact.text.orEmpty()
    val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withZone(ZoneId.systemDefault()).format(at)
    return "$date · ${PublishedAge.text(at, LocalNow.current)}"
}

@Composable
private fun offlineValue(fact: ItemFact.Offline): String {
    val what = stringResource(
        if (fact.audioOnly) R.string.item_fact_offline_sound else R.string.item_fact_offline_video
    )
    val size = fact.bytes?.let { "%.1f MB".format(it / BYTES_PER_MB) }
    return listOfNotNull(what, size).joinToString(" · ")
}

private fun kindLabel(fact: ItemFact.Kind): Int = when (fact.kind) {
    MediaContentKind.MUSIC -> R.string.item_kind_song
    MediaContentKind.LIVE -> R.string.item_kind_live
    MediaContentKind.SHORT -> R.string.item_kind_short
    MediaContentKind.STANDARD -> if (fact.isVideo) R.string.item_kind_video else R.string.item_kind_episode
}

internal const val ITEM_FACTS_TAG = "item-facts"
private const val LABEL_WEIGHT = 0.4f
private const val VALUE_WEIGHT = 0.6f
private const val BYTES_PER_MB = 1_000_000.0
