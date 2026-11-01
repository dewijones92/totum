package com.dewijones92.totum.ui.player

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.queue.TrailNavigation

val LocalTrail = staticCompositionLocalOf<TrailNavigation?> { null }

@Composable
internal fun TrailTiles() {
    val trail = LocalTrail.current ?: return
    val around by trail.around.collectAsStateWithLifecycle()
    around.back?.let { back ->
        ControlTile(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            label = stringResource(R.string.trail_back),
            value = back.item.title,
            active = false,
            onClick = trail::back,
            modifier = Modifier.testTag(TRAIL_BACK_TAG),
        )
    }
    around.forward?.let { forward ->
        ControlTile(
            icon = Icons.AutoMirrored.Outlined.ArrowForward,
            label = stringResource(R.string.trail_forward),
            value = forward.item.title,
            active = false,
            onClick = trail::forward,
            modifier = Modifier.testTag(TRAIL_FORWARD_TAG),
        )
    }
}

const val TRAIL_BACK_TAG = "trail-back"
const val TRAIL_FORWARD_TAG = "trail-forward"
