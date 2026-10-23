package com.dewijones92.totum.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import kotlinx.coroutines.delay

const val LOADING_OVERLAY_TAG: String = "loading-overlay"

@Composable
fun LoadingOverlay(loading: Boolean, modifier: Modifier = Modifier) {
    if (!loading) return
    var waitedSec by remember { mutableLongStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ONE_SECOND_MS)
            waitedSec++
        }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .background(Color.Black.copy(alpha = SCRIM_ALPHA), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag(LOADING_OVERLAY_TAG),
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp, modifier = Modifier.size(48.dp))
        Text(
            if (waitedSec >= SHOW_SECONDS_FROM) {
                stringResource(R.string.loading_waited, waitedSec)
            } else {
                stringResource(R.string.loading_now)
            },
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

private const val ONE_SECOND_MS = 1_000L
private const val SHOW_SECONDS_FROM = 2L
private const val SCRIM_ALPHA = 0.6f
