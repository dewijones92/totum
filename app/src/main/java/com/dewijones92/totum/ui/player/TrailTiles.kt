package com.dewijones92.totum.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.titleOf
import com.dewijones92.totum.queue.TrailNavigation
import com.dewijones92.totum.ui.common.LocalItemShield

val LocalTrail = staticCompositionLocalOf<TrailNavigation?> { null }

@Composable
internal fun TrailTiles() {
    val trail = LocalTrail.current ?: return
    val around by trail.around.collectAsStateWithLifecycle()
    val shield = LocalItemShield.current
    var listing by remember { mutableStateOf(false) }
    if (listing) TrailSheet(trail) { listing = false }
    val holdLabel = stringResource(R.string.trail_hold_label)
    around.back?.let { back ->
        ControlTile(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = stringResource(R.string.trail_back),
            value = shield.titleOf(back.item),
            active = false,
            onClick = trail::back,
            onLongClick = { listing = true },
            longClickLabel = holdLabel,
            description = stringResource(R.string.trail_back_description),
            modifier = Modifier.testTag(TRAIL_BACK_TAG),
        )
    }
    around.forward?.let { forward ->
        ControlTile(
            icon = Icons.AutoMirrored.Outlined.ArrowForward,
            label = stringResource(R.string.trail_forward),
            value = shield.titleOf(forward.item),
            active = false,
            onClick = trail::forward,
            onLongClick = { listing = true },
            longClickLabel = holdLabel,
            description = stringResource(R.string.trail_forward_description),
            modifier = Modifier.testTag(TRAIL_FORWARD_TAG),
        )
    }
}

const val TRAIL_BACK_TAG = "trail-back"
const val TRAIL_FORWARD_TAG = "trail-forward"
