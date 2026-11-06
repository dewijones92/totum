package com.dewijones92.totum.kidlock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.ui.common.VideoSurface
import com.dewijones92.totum.ui.player.FullscreenEffect
import kotlinx.coroutines.delay

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
    var shownUntil by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(shownUntil) {
        while (System.currentTimeMillis() < shownUntil) {
            delay(SHOW_TICK_MS)
            now = System.currentTimeMillis()
        }
        now = System.currentTimeMillis()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag(KID_LOCK_STAGE_TAG)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    shownUntil = System.currentTimeMillis() + SHOWN_MS
                    now = System.currentTimeMillis()
                    Diag.log(
                        "kidlock",
                        "touched while locked: the kid-mode badge and brightness show for ${SHOWN_MS}ms"
                    )
                }
            },
    ) {
        controller.player?.let { player ->
            VideoSurface(player = player, modifier = Modifier.aspectRatio(state?.videoAspectRatio ?: DEFAULT_ASPECT))
        }
        if (now < shownUntil) {
            KidModeBadge(
                onBrightness = { shownUntil = System.currentTimeMillis() + SHOWN_MS },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        if (!pinned) UnlockCorner(kidLock, Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun KidModeBadge(onBrightness: () -> Unit, modifier: Modifier = Modifier) {
    val window = (LocalActivity.current)?.window
    var level by remember {
        mutableFloatStateOf(window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: DEFAULT_BRIGHTNESS)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .padding(top = 24.dp)
            .widthIn(max = 420.dp)
            .background(Color.Black.copy(alpha = BADGE_ALPHA), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .testTag(KID_MODE_BADGE_TAG),
    ) {
        Text(stringResource(R.string.kid_mode_badge), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.LightMode,
                contentDescription = stringResource(R.string.kid_mode_brightness),
                tint = Color.White
            )
            Slider(
                value = level,
                onValueChange = { value ->
                    level = value
                    onBrightness()
                    window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = value } }
                },
                valueRange = MIN_BRIGHTNESS..1f,
                modifier = Modifier.width(260.dp).testTag(KID_MODE_BRIGHTNESS_TAG),
            )
        }
    }
}

const val KID_MODE_BADGE_TAG = "kid-mode-badge"
const val KID_MODE_BRIGHTNESS_TAG = "kid-mode-brightness"
private const val SHOWN_MS = 3_000L
private const val SHOW_TICK_MS = 250L
private const val BADGE_ALPHA = 0.6f
private const val MIN_BRIGHTNESS = 0.05f
private const val DEFAULT_BRIGHTNESS = 0.6f

@Composable
private fun UnlockCorner(kidLock: KidLock, modifier: Modifier) {
    Box(
        modifier
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
