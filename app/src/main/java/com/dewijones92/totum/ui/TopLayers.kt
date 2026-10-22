package com.dewijones92.totum.ui

import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.busy.BusyBar
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.kidlock.KidLockedStage

@Composable
internal fun TopLayers(container: AppContainer, still: Boolean, onLock: () -> Unit) {
    BusyBar(still = still, modifier = Modifier.safeDrawingPadding())
    val locked by container.kidLock.locked.collectAsStateWithLifecycle()
    LaunchedEffect(locked) { if (locked) onLock() }
    if (locked) KidLockedStage(container.playbackController, container.kidLock)
}
