package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.di.AppContainer

@Composable
fun QueueScreen(container: AppContainer, modifier: Modifier = Modifier) {
    val targets = container.targets
    val selected by targets.selected.collectAsStateWithLifecycle()
    DisposableEffect(targets) { onDispose { targets.queueTabLeft() } }
    WatchTargetsWhileShown(targets)
    Column(modifier = modifier.fillMaxSize()) {
        val remote = selected
        if (remote == null) {
            PhoneQueue(container, Modifier.weight(1f), above = { TargetChips(targets, watching = false) })
        } else {
            TargetChips(targets, watching = false)
            RemoteQueue(targets, remote, Modifier.weight(1f))
        }
    }
}
