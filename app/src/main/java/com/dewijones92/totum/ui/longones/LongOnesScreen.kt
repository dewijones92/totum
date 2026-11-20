package com.dewijones92.totum.ui.longones

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.longones.LongOnesState
import com.dewijones92.totum.operations.showing
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.ListTools
import com.dewijones92.totum.ui.common.ListToolsActions
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.applyTo
import com.dewijones92.totum.ui.common.listToolsField
import com.dewijones92.totum.ui.common.rememberListTools
import com.dewijones92.totum.ui.common.rowFacts
import kotlinx.coroutines.async

@Composable
fun LongOnesScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val longOnes = container.longOnes
    val resources = LocalResources.current
    var loads by remember { mutableIntStateOf(0) }
    LaunchedEffect(loads) {
        longOnes ?: return@LaunchedEffect
        Diag.log("longones", "dewidebug the Long ones page asked for a refresh (press $loads)")
        container.operations.showing("long-ones", resources.getString(R.string.longones_strip)) {
            container.applicationScope.async {
                runCatching { longOnes.refresh("the page opened or was refreshed") }
                    .onFailure { Diag.warn("longones", "the page's refresh failed", it) }
            }.await()
        }
    }
    val state by (longOnes?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(LongOnesState()) })
        .collectAsStateWithLifecycle()
    val suppressed by container.notForMe.items.collectAsStateWithLifecycle()
    val items = remember(state.items, suppressed) {
        state.items?.let { container.notForMe.without(it, "long ones page") }
    }
    val tools = rememberListTools("long ones")
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackHeader(stringResource(R.string.longones_title), onBack) {
                ListToolsActions(tools, items?.size ?: 0)
                IconButton(onClick = { loads++ }, modifier = Modifier.testTag("long-ones-refresh")) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.longones_refresh))
                }
            }
            if (items == null) {
                Text(
                    stringResource(R.string.longones_building),
                    modifier = Modifier.padding(16.dp).testTag("long-ones-building"),
                )
            } else {
                LongOnesList(container, tools, items, state)
            }
        }
    }
}

@Composable
private fun LongOnesList(container: AppContainer, tools: ListTools, all: List<MediaItem>, state: LongOnesState) {
    val items = tools.applyTo(all) { it }
    LazyColumn(Modifier.fillMaxSize().testTag("long-ones-list")) {
        item {
            Text(
                stringResource(R.string.longones_intro),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("long-ones-intro"),
            )
        }
        if (state.waitingForWifi) {
            item { Note(pluralStringResource(R.plurals.longones_wifi, state.lengthsWaiting, state.lengthsWaiting)) }
        } else if (state.lengthsWaiting > 0) {
            item {
                val waiting = state.lengthsWaiting
                Note(pluralStringResource(R.plurals.longones_lengths_waiting, waiting, waiting))
            }
        }
        listToolsField(tools, items.size, all.size)
        if (all.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.longones_empty),
                    modifier = Modifier.padding(16.dp).testTag("long-ones-empty"),
                )
            }
        }
        items(items, key = { it.id.value }) { LongOneRow(container, it) }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("long-ones-note"),
    )
}

@Composable
private fun LongOneRow(container: AppContainer, item: MediaItem) {
    val playable = item.asPlayable()
    MediaItemRow(
        item = item,
        subtitleLines = rowFacts(item, playable.pillar),
        pillar = playable.pillar,
        onPlay = {
            Diag.log("longones", "dewidebug playing \"${item.title}\" (${item.duration}) from the Long ones page")
            container.playbackQueue.playAll(listOf(playable))
        },
    )
}
