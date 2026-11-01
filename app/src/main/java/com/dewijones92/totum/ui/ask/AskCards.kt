package com.dewijones92.totum.ui.ask

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskCard
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.rowFacts
import kotlinx.coroutines.launch

@Composable
internal fun LatestBody(card: AskCard, container: AppContainer) {
    val items = card.items
    if (items.isEmpty()) {
        Text(stringResource(R.string.ask_no_latest), modifier = Modifier.padding(16.dp))
        return
    }
    val group = QueueGroup("ask:latest:${card.suggestion.title}", "🆕 ${card.suggestion.title}")
    items.forEach { playable ->
        MediaItemRow(
            item = playable.item,
            subtitleLines = rowFacts(playable.item, playable.handle.pillar),
            pillar = playable.handle.pillar,
            onPlay = {
                Diag.log("ask", "latest: play \"${playable.item.title}\" with the rest after it")
                container.playbackQueue.playAll(items, group, startAt = playable.item.id)
            },
        )
    }
    Row(Modifier.padding(horizontal = 16.dp)) {
        FilledTonalButton(onClick = {
            Diag.log("ask", "latest: play all ${items.size} from \"${card.suggestion.title}\"")
            container.playbackQueue.playAll(items, group)
        }) { Text(stringResource(R.string.ask_play_all)) }
        OutlinedButton(onClick = {
            Diag.log("ask", "latest: queue all ${items.size} from \"${card.suggestion.title}\"")
            container.playbackQueue.enqueueAll(items, group)
        }, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.ask_queue_all)) }
    }
}

@Composable
internal fun RadioBody(card: AskCard, container: AppContainer, row: @Composable (SearchHit) -> Unit) {
    val seeds = card.copies.filterIsInstance<SearchHit.Song>()
    if (seeds.isEmpty()) {
        Text(stringResource(R.string.ask_no_copy), modifier = Modifier.padding(16.dp))
        return
    }
    val scope = rememberCoroutineScope()
    FilledTonalButton(
        onClick = {
            Diag.log("ask", "radio \"${card.suggestion.title}\" from ${seeds.size} seed song(s)")
            scope.launch { container.musicRadio.startMix(seeds.map { it.song.videoId to it.song.title }) }
        },
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) { Text(stringResource(R.string.ask_start_radio)) }
    Text(
        stringResource(R.string.ask_radio_seeds),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    seeds.forEach { row(it) }
}
