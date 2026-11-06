package com.dewijones92.totum.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SnackbarOffers(val host: SnackbarHostState, private val scope: CoroutineScope) {
    fun offer(
        message: String,
        actionLabel: String,
        tag: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        onAction: () -> Unit,
    ) {
        scope.launch {
            val result = host.showSnackbar(message = message, actionLabel = actionLabel, duration = duration)
            Diag.log(tag, "dewidebug snackbar \"$message\" ($actionLabel): $result")
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }
}

val LocalSnackbarOffers = staticCompositionLocalOf<SnackbarOffers?> { null }

@Composable
fun rememberSnackbarOffers(
    host: SnackbarHostState,
    scope: CoroutineScope = rememberCoroutineScope(),
): SnackbarOffers = remember(host, scope) { SnackbarOffers(host, scope) }

@Composable
fun <T, R : Any> rememberRemoveWithUndo(
    offers: SnackbarOffers?,
    undoLabel: String,
    tag: String,
    duration: SnackbarDuration = SnackbarDuration.Short,
    removedMessage: (R) -> String,
    remove: (T) -> R?,
    restore: (R) -> Unit,
): (T) -> Unit {
    val message by rememberUpdatedState(removedMessage)
    val removeNow by rememberUpdatedState(remove)
    val restoreNow by rememberUpdatedState(restore)
    return remember(offers, undoLabel, tag, duration) {
        {
                target: T ->
            val removed = removeNow(target)
            if (removed == null) {
                Diag.log(tag, "dewidebug remove: nothing was removed, so no undo is offered")
            } else if (offers == null) {
                Diag.log(tag, "dewidebug removed with no snackbar host here, so no undo is offered")
            } else {
                offers.offer(message(removed), undoLabel, tag, duration) { restoreNow(removed) }
            }
        }
    }
}
