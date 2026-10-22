package com.dewijones92.totum.kidlock

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.ui.player.FullscreenEffect

const val KID_LOCK_STAGE_TAG = "kid-lock-stage"
private const val UNLOCK_HOLD_MS = 3_000L
private const val DEFAULT_ASPECT = 16f / 9f

@OptIn(UnstableApi::class)
@Composable
fun KidLockedStage(controller: PlaybackController, kidLock: KidLock) {
    val state by controller.state.collectAsStateWithLifecycle()
    val pinned by kidLock.pinnedBySystem.collectAsStateWithLifecycle()
    BackHandler { Diag.log("kidlock", "back pressed while locked; ignored") }
    FullscreenEffect(active = true, lockLandscape = true)
    val label = stringResource(R.string.kid_lock_stage)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(KID_LOCK_STAGE_TAG)
            .semantics { contentDescription = label }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown().consume() } },
    ) {
        controller.player?.let { player ->
            PlayerSurface(
                player = player,
                surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                modifier = Modifier.aspectRatio(state?.videoAspectRatio ?: DEFAULT_ASPECT),
            )
        }
        if (!pinned) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(72.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            val lifted = withTimeoutOrNull(UNLOCK_HOLD_MS) { waitForUpOrCancellation() != null }
                            if (lifted == null) kidLock.unlock("the top-left corner held for 3 s (Android pinning off)")
                        }
                    },
            )
        }
    }
}
