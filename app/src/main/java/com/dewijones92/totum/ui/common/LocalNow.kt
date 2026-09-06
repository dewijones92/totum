package com.dewijones92.totum.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The moment every list is drawn against, ticking once a minute so a "2 hours ago" becomes
 * "3 hours ago" while the screen stays open. One clock for the whole app, provided at the root,
 * because a row that computed its own would either never move or wake on its own schedule.
 */
val LocalNow = compositionLocalOf { Instant.now() }

@Composable
fun rememberTickingNow(): Instant {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(TICK_MS)
            now = Instant.now()
        }
    }
    return now
}

private const val TICK_MS = 60_000L
