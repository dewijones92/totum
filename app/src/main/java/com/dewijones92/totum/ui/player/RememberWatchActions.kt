package com.dewijones92.totum.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The account-side actions of the full player, read live from the watch view model. */
@Composable
internal fun rememberWatchActions(viewModel: WatchViewModel): WatchActions {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val rating by viewModel.rating.collectAsStateWithLifecycle()
    val inWatchLater by viewModel.inWatchLater.collectAsStateWithLifecycle()
    val postState by viewModel.postState.collectAsStateWithLifecycle()
    return WatchActions(
        canAct = signedIn,
        rating = rating,
        inWatchLater = inWatchLater,
        onToggleLike = viewModel::toggleLike,
        onToggleDislike = viewModel::toggleDislike,
        onToggleWatchLater = viewModel::toggleWatchLater,
        postState = postState,
        onPostComment = viewModel::postComment,
        onPostHandled = viewModel::clearPostState,
    )
}
