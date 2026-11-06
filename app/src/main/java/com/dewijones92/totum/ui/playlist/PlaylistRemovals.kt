package com.dewijones92.totum.ui.playlist

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.data.playlist.RemovedPlaylist
import com.dewijones92.totum.domain.LocalPlaylist
import com.dewijones92.totum.ui.common.HeldRemoval
import com.dewijones92.totum.ui.common.LocalSnackbarOffers
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo
import com.dewijones92.totum.ui.common.removeAndHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

typealias HeldPlaylists = HeldRemoval<List<String>, List<RemovedPlaylist>>

class PlaylistRemovals(private val store: LocalPlaylistStore, private val scope: CoroutineScope) {

    fun delete(playlists: List<LocalPlaylist>): HeldPlaylists? {
        if (playlists.isEmpty()) {
            Diag.log(TAG, "dewidebug delete: no playlists given, so no undo is offered")
            return null
        }
        val items = playlists.sumOf { it.itemCount }
        Diag.log(TAG, "dewidebug deleting ${playlists.size} playlist(s) holding $items item(s), undo offered")
        return scope.removeAndHold(TAG, playlists.map { it.name }) { playlists.mapNotNull { store.delete(it.id) } }
    }

    fun restore(held: HeldPlaylists) {
        scope.launch {
            val removed = held.taken()
            removed.forEach { store.restore(it) }
            val items = removed.sumOf { it.items.size }
            Diag.log(
                TAG,
                "dewidebug undo: put back ${removed.size} playlist(s) with $items item(s) in their old places"
            )
        }
    }

    private companion object {
        const val TAG = "playlist"
    }
}

@Composable
fun rememberDeletePlaylistsWithUndo(removals: PlaylistRemovals): (List<LocalPlaylist>) -> Unit {
    val resources = LocalResources.current
    return rememberRemoveWithUndo(
        offers = LocalSnackbarOffers.current,
        undoLabel = stringResource(R.string.undo),
        tag = "playlist",
        duration = SnackbarDuration.Long,
        removedMessage = { held: HeldPlaylists ->
            resources.getQuantityString(
                R.plurals.playlists_deleted,
                held.summary.size,
                held.summary.size,
                held.summary.first(),
            )
        },
        remove = removals::delete,
        restore = removals::restore,
    )
}
