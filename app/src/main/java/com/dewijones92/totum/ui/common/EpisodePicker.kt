package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.download.DownloadManager
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.ItemShield
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.domain.formatBytes
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.domain.titleOf
import com.dewijones92.totum.downloads.EpisodeDownloads
import com.dewijones92.totum.downloads.startsTicked
import com.dewijones92.totum.settings.DownloadChoice
import com.dewijones92.totum.settings.DownloadPlace

const val EPISODE_PICKER_TAG = "episode-picker"
const val EPISODE_EXTRAS_TAG = "episode-extras"
const val EPISODE_LIST_TAG = "episode-list"

@Composable
internal fun EpisodePicker(
    episodes: EpisodeDownloads,
    downloads: DownloadManager,
    remembered: DownloadChoice?,
    suggestedPlace: DownloadPlace? = null,
    qualityFor: (MediaItem, Int?) -> QualityLabel? = { _, _ -> null },
) {
    val ask by episodes.asking.collectAsStateWithLifecycle()
    val current = ask ?: return
    val states by downloads.observeDownloads().collectAsStateWithLifecycle(initialValue = null)
    val known = states ?: return
    var choice by remember(current) { mutableStateOf(remembered ?: DownloadChoice.Video(null)) }
    var place by remember(current) { mutableStateOf(suggestedPlace ?: DownloadPlace.PHONE) }
    val qualities = remember(current) {
        DownloadChoice.heights.associateWith { height ->
            current.items.take(QUALITY_PROBE).firstNotNullOfOrNull { qualityFor(it.item, height) }
        }
    }
    var ticked by remember(current) {
        mutableStateOf(
            current.items.filter { startsTicked(it, known[it.item.id], choice, it.item.id in current.extras) }
                .map { it.item.id }.toSet(),
        )
    }
    var extrasOpen by rememberSaveable(current.title) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = episodes::dismiss,
        modifier = Modifier.testTag(EPISODE_PICKER_TAG),
        title = { Text(stringResource(R.string.episodes_title, current.title)) },
        text = {
            PickerBody(
                ask = current,
                known = known,
                qualities = qualities,
                choice = choice,
                onChoice = { choice = it },
                place = place.takeIf { suggestedPlace != null && episodes.homeAvailable },
                onPlace = { place = it },
                ticked = ticked,
                onTicked = { ticked = it },
                extrasOpen = extrasOpen,
                onExtrasOpen = { extrasOpen = it },
            )
        },
        confirmButton = {
            TextButton(
                onClick = { episodes.start(current, ticked, choice, place) },
                enabled = ticked.isNotEmpty(),
            ) { Text(stringResource(R.string.episodes_download, ticked.size)) }
        },
        dismissButton = { TextButton(onClick = episodes::dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun PickerBody(
    ask: EpisodeDownloads.Ask,
    known: Map<MediaItemId, DownloadState>,
    qualities: Map<Int?, QualityLabel?>,
    choice: DownloadChoice,
    onChoice: (DownloadChoice) -> Unit,
    place: DownloadPlace?,
    onPlace: (DownloadPlace) -> Unit,
    ticked: Set<MediaItemId>,
    onTicked: (Set<MediaItemId>) -> Unit,
    extrasOpen: Boolean,
    onExtrasOpen: (Boolean) -> Unit,
) {
    val filter = rememberListFilter("episode picker")
    val shape = rememberListShape("episode picker")
    val ordered = ask.items.shapedBy(shape) { it.item }
    val shown = filter.filter(ordered, { it.item.searchableText })
    val (extraRows, mainRows) = shown.partition { it.item.id in ask.extras }
    val shownIds = (if (extrasOpen) shown else mainRows).mapTo(mutableSetOf()) { it.item.id }
    LazyColumn(Modifier.fillMaxWidth().testTag(EPISODE_LIST_TAG)) {
        item(key = "on-phone") {
            val onPhone = ask.items.count { known[it.item.id] is DownloadState.Downloaded }
            Text(stringResource(R.string.episodes_on_phone, onPhone, ask.items.size))
        }
        if (place != null) item(key = "place") { PlaceChips(place, onPlace) }
        if (ask.offersAChoice) item(key = "choice") { ChoiceChips(choice, qualities, onChoice) }
        item(key = "shape") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                ShapeControl(shape)
            }
        }
        item(key = "filter") { FilterField(filter, shown.size, ordered.size, inset = 0.dp) }
        if (filter.filtering && shown.isEmpty()) {
            item(key = "no-matches") { NoFilterMatches(filter.query) }
            return@LazyColumn
        }
        item(key = "all-none") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onTicked(ticked + shownIds) }) { Text(stringResource(R.string.episodes_all)) }
                TextButton(onClick = { onTicked(ticked - shownIds) }) { Text(stringResource(R.string.episodes_none)) }
            }
        }
        val onTick = { id: MediaItemId, on: Boolean -> onTicked(if (on) ticked + id else ticked - id) }
        pickerRows(mainRows, ask, known, ticked, onTick)
        if (extraRows.isNotEmpty()) {
            item(key = "extras-toggle") {
                TextButton(onClick = { onExtrasOpen(!extrasOpen) }, modifier = Modifier.testTag(EPISODE_EXTRAS_TAG)) {
                    Text(
                        stringResource(
                            if (extrasOpen) R.string.episodes_extras_open else R.string.episodes_extras,
                            extraRows.size,
                            formatBytes(extraRows.sumOf { sizeOf(ask, it) }),
                        ),
                    )
                }
            }
            if (extrasOpen) pickerRows(extraRows, ask, known, ticked, onTick)
        }
    }
}

private fun LazyListScope.pickerRows(
    rows: List<PlayableItem>,
    ask: EpisodeDownloads.Ask,
    known: Map<MediaItemId, DownloadState>,
    ticked: Set<MediaItemId>,
    onTick: (MediaItemId, Boolean) -> Unit,
) {
    items(rows, key = { it.item.id.value }) { item ->
        val plays = LocalPlayStates.current
        val shield = LocalItemShield.current
        PickerRow(item, ask, known, plays, shield, checked = item.item.id in ticked) { on ->
            onTick(item.item.id, on)
        }
    }
}

private fun sizeOf(ask: EpisodeDownloads.Ask, item: PlayableItem): Long = ask.details[item.item.id]?.sizeBytes ?: 0L

@Composable
private fun PickerRow(
    item: PlayableItem,
    ask: EpisodeDownloads.Ask,
    known: Map<MediaItemId, DownloadState>,
    plays: State<Map<MediaItemId, PlayState>>,
    shield: ItemShield,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val itemId = item.item.id
    val played by remember(plays, itemId) { derivedStateOf { plays.value[itemId] } }
    val detail = ask.details[itemId]
    val note = listOfNotNull(
        stateNote(known[itemId]),
        playNote(played),
        detail?.sizeBytes?.takeIf { it > 0 }?.let(::formatBytes),
        item.item.duration?.let(::formatClock),
        stringResource(R.string.episodes_extra).takeIf { itemId in ask.extras },
    ).joinToString(" · ").ifEmpty { null }
    val title = shield.titleOf(item.item)
    LabelledCheckbox(checked, if (note == null) title else "$title · $note", onChange = onChange)
}

@Composable
private fun ChoiceChips(
    choice: DownloadChoice,
    qualities: Map<Int?, QualityLabel?>,
    onChoose: (DownloadChoice) -> Unit,
) {
    val options = listOf(DownloadChoice.Sound) + DownloadChoice.heights.map { DownloadChoice.Video(it) }
    Column(Modifier.padding(vertical = 4.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == choice,
                onClick = { onChoose(option) },
                label = {
                    Text(
                        when (option) {
                            DownloadChoice.Sound -> stringResource(R.string.download_choice_sound)
                            is DownloadChoice.Video -> videoChoiceText(option.maxHeight, qualities[option.maxHeight])
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun stateNote(state: DownloadState?): String? = when (state) {
    is DownloadState.Downloaded -> stringResource(R.string.episodes_state_on_phone)
    is DownloadState.Downloading -> stringResource(R.string.episodes_state_downloading)
    is DownloadState.Failed -> stringResource(R.string.episodes_state_failed)
    else -> null
}

@Composable
private fun playNote(state: PlayState?): String? = when (state) {
    is PlayState.Played -> stringResource(R.string.episodes_state_watched)
    is PlayState.InProgress -> (state.positionMs / MS_PER_MINUTE).let { minutes ->
        pluralStringResource(R.plurals.episodes_state_started, minutes.toInt(), minutes)
    }
    else -> null
}

private const val MS_PER_MINUTE = 60_000L
private const val QUALITY_PROBE = 5
