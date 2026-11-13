package com.dewijones92.totum.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.CarryOnItem
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.withArtworkFrom
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.ui.common.ItemActionSheet
import com.dewijones92.totum.ui.common.LocalSourceArtwork
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.PlayProgressSliver
import com.dewijones92.totum.ui.common.TrackedViewModel
import com.dewijones92.totum.ui.common.durationLabel
import com.dewijones92.totum.ui.common.face
import com.dewijones92.totum.ui.common.liveNow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

internal data class LibraryCarryOn(
    val items: List<CarryOnItem> = emptyList(),
    val onPlay: (CarryOnItem) -> Unit = {},
)

class CarryOnViewModel(
    playHistory: PlayHistoryStore,
    notForMe: NotForMe,
    private val queue: PlaybackQueue,
) : TrackedViewModel("carry-on") {

    val history: StateFlow<List<PlayableItem>> = combine(playHistory.observe(), notForMe.items) { played, _ ->
        notForMe.without(played, "the Carry on row") { it.item.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    fun play(entry: CarryOnItem) {
        val item = entry.playable.item
        Diag.log(
            "carry-on",
            "dewidebug \"${item.title}\" [id=${item.id.value}] tapped to carry on from " +
                "${entry.progress.positionMs}ms of ${entry.progress.durationMs ?: "an unknown length"}ms",
        )
        queue.playAll(listOf(entry.playable))
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { CarryOnViewModel(container.playHistoryStore, container.notForMe, container.playbackQueue) }
        }
    }
}

internal fun LazyListScope.carryOnSection(carryOn: LibraryCarryOn) {
    if (carryOn.items.isEmpty()) return
    item(key = "carry-on") {
        Column(Modifier.testTag(CARRY_ON_TAG)) {
            Text(
                text = stringResource(R.string.carry_on_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(carryOn.items, key = { "carry-on:" + it.playable.item.id.value }) { entry ->
                    CarryOnCard(entry) { carryOn.onPlay(entry) }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarryOnCard(entry: CarryOnItem, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val item = entry.playable.item
    val face = item.face()
    val live = liveNow(item.id, item.contentKind)
    Column(
        modifier = Modifier
            .width(CARD_WIDTH)
            .combinedClickable(onClick = onPlay, onLongClick = {
                Diag.log("carry-on", "dewidebug long-pressed \"${item.title}\" [id=${item.id.value}]")
                menu = true
            })
            .padding(vertical = 4.dp)
            .testTag(carryOnCardTag(item.id.value)),
    ) {
        MediaThumbnail(
            url = item.withArtworkFrom(LocalSourceArtwork.current).thumbnailUrl,
            contentDescription = face.title,
            modifier = Modifier.size(width = CARD_WIDTH, height = CARD_HEIGHT),
            durationLabel = durationLabel(face).takeUnless { live },
            blurred = face.blurThumbnail,
        )
        if (!live) PlayProgressSliver(entry.progress, Modifier.width(CARD_WIDTH))
        Text(text = face.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        Text(
            text = progressWords(entry.progress),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (menu) ItemActionSheet(item, onDismiss = { menu = false }, pillar = entry.playable.pillar)
}

@Composable
private fun progressWords(progress: PlayState.InProgress): String {
    val minutes = (progress.positionMs / MS_PER_MINUTE).toInt()
    val total = progress.durationMs?.let { (it / MS_PER_MINUTE).toInt() }?.takeIf { it > 0 }
        ?: return pluralStringResource(R.plurals.episodes_state_started, minutes, minutes)
    return pluralStringResource(R.plurals.carry_on_progress, total, minutes, total)
}

fun carryOnCardTag(id: String): String = "carry-on-card:$id"

const val CARRY_ON_TAG = "carry-on-row"
private const val MS_PER_MINUTE = 60_000L
private val CARD_WIDTH = 160.dp
private val CARD_HEIGHT = 90.dp
