package com.dewijones92.totum.ui.library

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.JellyfinApi
import com.dewijones92.totum.data.home.playable
import com.dewijones92.totum.data.jellyfin.JellyfinItem
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.ListThenDetail
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.NameSort
import com.dewijones92.totum.ui.common.NamedListTools
import com.dewijones92.totum.ui.common.NamedListToolsActions
import com.dewijones92.totum.ui.common.applyTo
import com.dewijones92.totum.ui.common.namedListToolsField
import com.dewijones92.totum.ui.common.rememberNamedListTools
import com.dewijones92.totum.ui.common.rowFacts
import kotlinx.coroutines.launch

@Composable
fun JellyfinScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val api = container.jellyfin ?: return
    var trail by rememberSaveable { mutableStateOf(listOf<String>()) }
    val read = remember { mutableMapOf<String?, JellyfinListing>() }
    val up = { trail = trail.dropLast(1) }
    val open = { id: String -> trail = trail + id }
    ListThenDetail(trail.lastOrNull(), listKey = "jellyfin", onBack = up, list = {
        Listing(container, api, null, read, onBack, open, modifier)
    }) { parent ->
        Listing(container, api, parent, read, up, open, modifier)
    }
}

@Composable
private fun Listing(
    container: AppContainer,
    api: JellyfinApi,
    parent: String?,
    read: MutableMap<String?, JellyfinListing>,
    back: () -> Unit,
    open: (String) -> Unit,
    modifier: Modifier,
) {
    val subject = stringResource(R.string.library_jellyfin)
    var attempt by remember(parent) { mutableIntStateOf(0) }
    var answer by remember(parent, attempt) {
        mutableStateOf<HomeAnswer<JellyfinListing>?>(read[parent]?.takeIf { attempt == 0 }?.let { HomeAnswer.Ok(it) })
    }
    var loadingMore by remember(parent, attempt) { mutableStateOf(false) }
    val tools = rememberNamedListTools("jellyfin ${parent ?: "top"}")
    val scope = rememberCoroutineScope()
    LaunchedEffect(parent, attempt) {
        if (answer == null) answer = readPage(container, api, parent, 0, subject)
    }
    LaunchedEffect(answer) { (answer as? HomeAnswer.Ok)?.let { read[parent] = it.value } }
    Column(modifier.fillMaxSize()) {
        val shown = answer
        BackHeader((shown as? HomeAnswer.Ok)?.value?.title ?: subject, back) {
            (shown as? HomeAnswer.Ok)?.let { NamedListToolsActions(tools, it.value.items.size, JELLYFIN_SORTS) }
        }
        when (shown) {
            null -> Unit
            is HomeAnswer.No -> Failed(shown.reason) { attempt++ }
            is HomeAnswer.Ok -> Items(container, api, shown.value, tools, loadingMore, onMore = {
                val next = shown.value.next ?: return@Items
                loadingMore = true
                scope.launch {
                    when (val more = readPage(container, api, parent, next, subject)) {
                        is HomeAnswer.Ok -> answer = HomeAnswer.Ok(
                            shown.value.copy(items = shown.value.items + more.value.items, next = more.value.next),
                        )
                        is HomeAnswer.No -> Diag.warn(
                            "jellyfin",
                            "more of ${parent ?: "top"} from $next: ${more.reason}"
                        )
                    }
                    loadingMore = false
                }
            }) { open(it) }
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
    tools: NamedListTools,
    loadingMore: Boolean,
    onMore: () -> Unit,
    open: (String) -> Unit,
) {
    val visible = tools.applyTo(
        listing.items,
        title = { it.title },
        date = { it.year?.toLong() },
        fields = { listOf(it.title, it.seriesName, it.year?.toString()) },
    )
    if (listing.items.isEmpty()) {
        Text(stringResource(R.string.jellyfin_empty), Modifier.padding(20.dp))
        return
    }
    val byId = listing.items.filter { it.kind.playable }.associate { it.id to api.playable(it) }
    val playables = byId.values.toList()
    val group = QueueGroup("jellyfin:${listing.parent ?: "top"}", listing.title)
    LazyColumn(Modifier.fillMaxWidth()) {
        namedListToolsField(tools, visible.size, listing.items.size)
        items(visible, key = { it.id }) { item ->
            val playable = byId[item.id]
            if (playable != null) {
                MediaItemRow(
                    item = playable.item,
                    subtitleLines = rowFacts(playable.item, playable.pillar),
                    pillar = playable.pillar,
                    onPlay = {
                        Diag.log(
                            "jellyfin",
                            "play ${item.id} (${item.title}) with ${playables.size} from \"${group.title}\""
                        )
                        container.playbackQueue.playAll(playables, group, startAt = playable.item.id)
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

private val JELLYFIN_SORTS = listOf(NameSort.AS_LISTED, NameSort.TITLE, NameSort.NEWEST, NameSort.OLDEST)
