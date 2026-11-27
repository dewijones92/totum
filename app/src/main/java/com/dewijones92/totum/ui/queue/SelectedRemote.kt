package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.DockBar
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.dockBarFor
import com.dewijones92.totum.targets.TargetsRepository

internal data class SelectedRemote(val state: TargetState, val colour: Color)

internal sealed interface DockShown {
    data class Remote(val remote: SelectedRemote) : DockShown

    data object Phone : DockShown

    data object Hidden : DockShown
}

@Composable
internal fun rememberDockShown(targets: TargetsRepository, onQueueTab: Boolean): DockShown {
    if (!targets.available) return DockShown.Phone
    val listed by targets.targets.collectAsStateWithLifecycle()
    val selected by targets.selected.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val bar = listed.dockBarFor(selected, onQueueTab)
    val why = when {
        selected == null -> "this phone is the chosen device"
        bar is DockBar.Remote ->
            "${bar.state.target.label} is chosen and has ${bar.state.entries.size} queued, " +
                "at=${bar.state.at}"
        bar is DockBar.Hidden -> "$selected is chosen and its remote page is on screen with the same controls"
        listed.none { it.target.id == selected } -> "$selected is chosen but the Pi does not list it"
        else -> "$selected is chosen but has nothing playing or queued to show"
    }
    val owner = when (bar) {
        is DockBar.Remote -> "the remote for ${bar.state.target.label}"
        DockBar.Hidden -> "not drawn on the Queue tab while $selected's remote page shows its controls"
        DockBar.Phone -> "the phone's"
    }
    LaunchedEffect(owner, selected, onQueueTab) { Diag.log("targets", "dock bar is $owner: $why") }
    return when (bar) {
        is DockBar.Remote ->
            DockShown.Remote(SelectedRemote(bar.state, TargetColours.of(colours, bar.state.target.id).asColour()))
        DockBar.Hidden -> DockShown.Hidden
        DockBar.Phone -> DockShown.Phone
    }
}
