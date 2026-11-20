package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.barFor
import com.dewijones92.totum.targets.TargetsRepository

internal data class SelectedRemote(val state: TargetState, val colour: Color)

@Composable
internal fun rememberSelectedRemote(targets: TargetsRepository): SelectedRemote? {
    if (!targets.available) return null
    val listed by targets.targets.collectAsStateWithLifecycle()
    val selected by targets.selected.collectAsStateWithLifecycle()
    val colours by targets.colours.collectAsStateWithLifecycle()
    val state = listed.barFor(selected)
    val why = when {
        selected == null -> "this phone is the chosen device"
        state != null -> "${state.target.label} is chosen and has ${state.entries.size} queued, at=${state.at}"
        listed.none { it.target.id == selected } -> "$selected is chosen but the Pi does not list it"
        else -> "$selected is chosen but has nothing playing or queued to show"
    }
    LaunchedEffect(state?.target?.id, selected) {
        val owner = if (state != null) "the remote for ${state.target.label}" else "the phone's"
        Diag.log("targets", "dock bar is $owner: $why")
    }
    return state?.let { SelectedRemote(it, TargetColours.of(colours, it.target.id).asColour()) }
}
