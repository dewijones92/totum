package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R

class QueueSkip(val previous: () -> Unit, val next: () -> Unit)

val LocalQueueSkip = compositionLocalOf<QueueSkip?> { null }

@Composable
fun SkipPreviousButton(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val skip = LocalQueueSkip.current ?: return
    IconButton(onClick = skip.previous, modifier = modifier.testTag(SKIP_PREVIOUS_TAG)) {
        Icon(Icons.Filled.SkipPrevious, stringResource(R.string.skip_to_previous), Modifier.size(size))
    }
}

@Composable
fun SkipNextButton(modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val skip = LocalQueueSkip.current ?: return
    IconButton(onClick = skip.next, modifier = modifier.testTag(SKIP_NEXT_TAG)) {
        Icon(Icons.Filled.SkipNext, stringResource(R.string.skip_to_next), Modifier.size(size))
    }
}

const val SKIP_PREVIOUS_TAG = "skip-previous"
const val SKIP_NEXT_TAG = "skip-next"
