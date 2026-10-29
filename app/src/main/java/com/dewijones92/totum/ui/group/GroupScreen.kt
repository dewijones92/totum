package com.dewijones92.totum.ui.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.asPlayable
import com.dewijones92.totum.operations.showing
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.rowFacts
import java.time.Instant

@Composable
internal fun GroupScreen(container: AppContainer, groupId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val groups by container.sourceGroupStore.observeGroups().collectAsStateWithLifecycle(initialValue = null)
    val group = groups?.firstOrNull { it.id.value == groupId }
    LaunchedEffect(groups, group) {
        if (groups != null && group == null) {
            Diag.log("group", "group $groupId is gone (deleted); leaving its page")
            onBack()
        }
    }
    group ?: return
    val resources = LocalResources.current
    var since by remember(groupId) { mutableStateOf<Instant?>(null) }
    var items by remember(groupId) { mutableStateOf<List<MediaItem>?>(null) }
    var loads by remember { mutableIntStateOf(0) }
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(groupId) { since = container.groupNews.opened(group.id) }
    LaunchedEffect(groupId, group.members, loads) {
        if (group.members.isEmpty()) return@LaunchedEffect
        val label = resources.getString(R.string.group_loading_strip, group.name)
        items = container.operations.showing("group:$groupId", label) {
            container.groupFeed.itemsFor(group)
        }
    }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackHeader(group.name, onBack) {
                IconButton(onClick = { loads++ }, modifier = Modifier.testTag("group-refresh")) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.group_refresh))
                }
                IconButton(onClick = { editing = true }, modifier = Modifier.testTag("group-edit")) {
                    Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.group_edit))
                }
            }
            val shown = items
            when {
                group.members.isEmpty() -> NoMembers { editing = true }
                shown == null -> Text(
                    stringResource(R.string.group_loading, group.name),
                    modifier = Modifier.padding(16.dp),
                )
                else -> GroupList(container, shown, since)
            }
        }
    }
    if (editing) GroupEditSheet(container, group, onDismiss = { editing = false }, onDeleted = onBack)
}

@Composable
private fun NoMembers(onEdit: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(stringResource(R.string.group_no_members), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onEdit, modifier = Modifier.testTag("group-add-channels")) {
            Text(stringResource(R.string.group_add_channels))
        }
    }
}

@Composable
private fun GroupList(container: AppContainer, items: List<MediaItem>, since: Instant?) {
    val fresh = since?.let { s -> items.filter { it.publishedAt?.isAfter(s) == true } }.orEmpty()
    val older = items - fresh.toSet()
    LazyColumn(Modifier.fillMaxSize().testTag("group-list")) {
        if (fresh.isNotEmpty()) {
            item { Divider(stringResource(R.string.group_new_since, fresh.size), "group-new-divider") }
            items(fresh, key = { "new:" + it.id.value }) { GroupRow(container, it, isNew = true) }
            item { Divider(stringResource(R.string.group_earlier), "group-earlier-divider") }
        }
        items(older, key = { it.id.value }) { GroupRow(container, it, isNew = false) }
    }
}

@Composable
private fun Divider(text: String, tag: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag(tag),
    )
}

@Composable
private fun GroupRow(container: AppContainer, item: MediaItem, isNew: Boolean) {
    val playable = item.asPlayable()
    val facts = rowFacts(item, playable.pillar)
    MediaItemRow(
        item = item,
        subtitleLines = if (isNew) listOf(stringResource(R.string.group_item_new)) + facts else facts,
        pillar = playable.pillar,
        onPlay = {
            Diag.log("group", "playing \"${item.title}\" from a group page (new=$isNew)")
            container.playbackQueue.playAll(listOf(playable))
        },
    )
}
