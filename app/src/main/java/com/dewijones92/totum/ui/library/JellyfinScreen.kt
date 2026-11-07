package com.dewijones92.totum.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.JellyfinApi
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.jellyfin.toPlayable
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.ScreenHeader
import kotlinx.coroutines.launch

@Composable
fun JellyfinScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val api = container.jellyfin ?: return
    var trail by rememberSaveable { mutableStateOf(listOf<String>()) }
    var attempt by remember { mutableIntStateOf(0) }
    val parent = trail.lastOrNull()
    val subject = stringResource(R.string.library_jellyfin)
    var answer by remember(parent, attempt) { mutableStateOf<HomeAnswer<JellyfinListing>?>(null) }
    var loadingMore by remember(parent, attempt) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(parent, attempt) { answer = readPage(container, api, parent, 0, subject) }
    BackHandler {
        if (trail.isEmpty()) onBack() else trail = trail.dropLast(1)
    }
    Column(modifier.fillMaxSize()) {
        val shown = answer
        val title = (shown as? HomeAnswer.Ok)?.value?.title ?: stringResource(R.string.library_jellyfin)
        ScreenHeader(title) {
            TextButton(onClick = { if (trail.isEmpty()) onBack() else trail = trail.dropLast(1) }) {
                Text(stringResource(R.string.jellyfin_back))
            }
        }
        when (shown) {
            null -> Unit
            is HomeAnswer.No -> Failed(shown.reason) { attempt++ }
            is HomeAnswer.Ok -> Items(container, api, shown.value, loadingMore, onMore = {
                val next = shown.value.next ?: return@Items
                loadingMore = true
                scope.launch {
                    when (val more = readPage(container, api, parent, next, subject)) {
                        is HomeAnswer.Ok -> answer = HomeAnswer.Ok(
                            shown.value.copy(items = shown.value.items + more.value.items, next = more.value.next),
                        )
                        is HomeAnswer.No -> Diag.warn("jellyfin", "more of ${parent ?: "top"} from $next: ${more.reason}")
                    }
                    loadingMore = false
                }
            }) { trail = trail + it }
        }
    }
}

@Composable
private fun Failed(reason: String, retry: () -> Unit) {
    Column(Modifier.padding(20.dp)) {
        Text(reason, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = retry) { Text(stringResource(R.string.jellyfin_retry)) }
    }
}

@Composable
private fun Items(
    container: AppContainer,
    api: JellyfinApi,
    listing: JellyfinListing,
    loadingMore: Boolean,
    onMore: () -> Unit,
    open: (String) -> Unit,
) {
    if (listing.items.isEmpty()) {
        Text(stringResource(R.string.jellyfin_empty), Modifier.padding(20.dp))
        return
    }
    val playables = listing.items.filter { it.kind.playable }.map { it to it.toPlayable(api.streamUrl(it)) }
    val group = QueueGroup("jellyfin:${listing.parent ?: "top"}", listing.title)
    LazyColumn(Modifier.fillMaxWidth()) {
        items(listing.items, key = { it.id }) { item ->
            if (item.kind.playable) {
                val playable = item.toPlayable(api.streamUrl(item))
                MediaItemRow(
                    item = playable.item,
                    subtitleLines = item.facts,
                    pillar = playable.pillar,
                    onPlay = {
                        Diag.log(
                            "jellyfin",
                            "play ${item.id} (${item.title}) with ${playables.size} from \"${group.title}\""
                        )
                        container.playbackQueue.playAll(playables.map { it.second }, group, startAt = playable.item.id)
                    },
                )
            } else {
                Folder(item) { open(item.id) }
            }
        }
        if (listing.next != null) {
            item(key = "more") {
                TextButton(onClick = onMore, enabled = !loadingMore, modifier = Modifier.padding(horizontal = 12.dp)) {
                    Text(stringResource(R.string.jellyfin_more))
                }
            }
        }
    }
}

private suspend fun readPage(
    container: AppContainer,
    api: JellyfinApi,
    parent: String?,
    start: Int,
    subject: String,
): HomeAnswer<JellyfinListing> {
    var answer: HomeAnswer<JellyfinListing> = HomeAnswer.No("❌ Jellyfin was not read")
    container.operations.track("jellyfin:${parent ?: "top"}:$start", OperationKind.OPEN_PAGE, subject) {
        answer = api.browse(parent, start)
        when (val got = answer) {
            is HomeAnswer.Ok -> OperationEnd.Done(got.value.items.size)
            is HomeAnswer.No -> OperationEnd.Failed(got.reason)
        }
    }
    return answer
}

@Composable
private fun Folder(item: JellyfinItem, open: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text("${item.kind.emoji} ${item.name}", style = MaterialTheme.typography.titleMedium)
        if (item.facts.isNotEmpty()) Text(item.facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}
