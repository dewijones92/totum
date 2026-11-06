package com.dewijones92.totum.ui.history

import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.ui.common.HeldRemoval
import com.dewijones92.totum.ui.common.LocalSnackbarOffers
import com.dewijones92.totum.ui.common.rememberRemoveWithUndo
import com.dewijones92.totum.ui.common.removeAndHold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

typealias HeldHistory = HeldRemoval<Int, List<PlayableItem>>

class HistoryClearing(private val store: PlayHistoryStore, private val scope: CoroutineScope) {

    fun clear(shown: Int): HeldHistory? {
        if (shown == 0) {
            Diag.log(TAG, "dewidebug clear: the history is empty, so no undo is offered")
            return null
        }
        Diag.log(TAG, "dewidebug clearing the play history ($shown entries shown), undo offered")
        return scope.removeAndHold(TAG, shown) { store.clear() }
    }

    fun restore(held: HeldHistory) {
        scope.launch {
            val cleared = held.taken()
            store.restore(cleared)
            Diag.log(TAG, "dewidebug undo: put back ${cleared.size} play history entries in their old order")
        }
    }

    private companion object {
        const val TAG = "history"
    }
}

@Composable
fun rememberClearHistoryWithUndo(clearing: HistoryClearing): (Int) -> Unit {
    val resources = LocalResources.current
    return rememberRemoveWithUndo(
        offers = LocalSnackbarOffers.current,
        undoLabel = stringResource(R.string.undo),
        tag = "history",
        duration = SnackbarDuration.Long,
        removedMessage = { held: HeldHistory ->
            resources.getQuantityString(R.plurals.history_cleared, held.summary, held.summary)
        },
        remove = clearing::clear,
        restore = clearing::restore,
    )
}
