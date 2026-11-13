package com.dewijones92.totum.ui.group

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.group.SourceGroupStore
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceGroup
import kotlinx.coroutines.launch

internal class GroupActions(private val store: SourceGroupStore) {

    suspend fun toggle(group: SourceGroup, source: MediaSource) {
        val added = store.toggleMember(group.id, source)
        Diag.log("group", "\"${source.title}\" ${if (added) "added to" else "removed from"} \"${group.name}\"")
    }

    suspend fun rename(group: SourceGroup, name: String) {
        store.rename(group.id, name)
        Diag.log("group", "renamed \"${group.name}\" to \"$name\"")
    }

    suspend fun delete(group: SourceGroup) {
        store.delete(group.id)
        Diag.log("group", "deleted \"${group.name}\" (${group.members.size} members)")
    }

    suspend fun createWith(name: String, source: MediaSource) {
        val id = store.create(name)
        store.toggleMember(id, source)
        Diag.log("group", "created \"$name\" starting with \"${source.title}\"")
    }
}

@Composable
internal fun rememberGroupPicker(container: AppContainer): (MediaSource) -> Unit {
    var target by remember { mutableStateOf<MediaSource?>(null) }
    target?.let { source -> GroupPickerFor(container, source) { target = null } }
    return { target = it }
}

@Composable
private fun GroupPickerFor(container: AppContainer, source: MediaSource, onDismiss: () -> Unit) {
    val store = container.sourceGroupStore
    val groups by store.observeGroups().collectAsStateWithLifecycle(emptyList())
    val actions = remember(store) { GroupActions(store) }
    val scope = container.applicationScope
    GroupPicker(
        sourceId = source.id,
        groups = groups,
        onToggle = { group -> scope.launch { actions.toggle(group, source) } },
        onCreate = { name -> scope.launch { actions.createWith(name, source) } },
        onRename = { group, name -> scope.launch { actions.rename(group, name) } },
        onDelete = { group -> scope.launch { actions.delete(group) } },
        onDismiss = onDismiss,
    )
}
