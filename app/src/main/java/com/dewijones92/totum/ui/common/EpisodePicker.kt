package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.downloads.EpisodeDownloads
import com.dewijones92.totum.downloads.startsTicked
import com.dewijones92.totum.settings.DownloadChoice

const val EPISODE_PICKER_TAG = "episode-picker"

@Composable
internal fun EpisodePicker(episodes: EpisodeDownloads, downloads: DownloadManager, remembered: DownloadChoice?) {
    val ask by episodes.asking.collectAsStateWithLifecycle()
    val current = ask ?: return
    val states by downloads.observeDownloads().collectAsStateWithLifecycle(initialValue = null)
    val known = states ?: return
    var choice by remember(current) { mutableStateOf(remembered ?: DownloadChoice.Video(null)) }
    var ticked by remember(current) {
        mutableStateOf(current.items.filter { startsTicked(it, known[it.item.id], choice) }.map { it.item.id }.toSet())
    }
    val onPhone = current.items.count { known[it.item.id] is DownloadState.Downloaded }
    val plays = LocalPlayStates.current
    AlertDialog(
        onDismissRequest = episodes::dismiss,
        modifier = Modifier.testTag(EPISODE_PICKER_TAG),
        title = { Text(stringResource(R.string.episodes_title, current.title)) },
        text = {
            Column {
                Text(stringResource(R.string.episodes_on_phone, onPhone, current.items.size))
                if (current.offersAChoice) ChoiceChips(choice) { choice = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { ticked = current.items.mapTo(mutableSetOf()) { it.item.id } }) {
                        Text(stringResource(R.string.episodes_all))
                    }
                    TextButton(onClick = { ticked = emptySet() }) { Text(stringResource(R.string.episodes_none)) }
                }
                LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                    items(current.items, key = { it.item.id.value }) { item ->
                        val id = item.item.id
                        val played by remember(plays, id) { derivedStateOf { plays.value[id] } }
                        val note = listOfNotNull(stateNote(known[id]), playNote(played))
                            .joinToString(" · ").ifEmpty { null }
                        LabelledCheckbox(
                            id in ticked,
                            if (note == null) item.item.title else "${item.item.title} · $note"
                        ) { on ->
                            ticked = if (on) ticked + id else ticked - id
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { episodes.start(current, ticked, choice) },
                enabled = ticked.isNotEmpty(),
            ) { Text(stringResource(R.string.episodes_download, ticked.size)) }
        },
        dismissButton = { TextButton(onClick = episodes::dismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ChoiceChips(choice: DownloadChoice, onChoose: (DownloadChoice) -> Unit) {
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
                            is DownloadChoice.Video -> option.maxHeight?.let {
                                stringResource(R.string.download_choice_video_height, it)
                            } ?: stringResource(R.string.download_choice_video_best)
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
private val LIST_MAX_HEIGHT = 320.dp
