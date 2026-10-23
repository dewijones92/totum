package com.dewijones92.totum.ui.cast

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.playback.PlaybackController

val LocalScreenControl = staticCompositionLocalOf<PlaybackController?> { null }

const val SCREEN_BUTTON_TAG: String = "screen-button"

@Composable
fun ScreenButton(modifier: Modifier = Modifier, onDark: Boolean = false) {
    val controller = LocalScreenControl.current ?: return
    val targets by controller.screens.collectAsStateWithLifecycle()
    val casting by controller.castDevice.collectAsStateWithLifecycle()
    if (targets.present.isEmpty()) return
    val onAScreen = targets.present.any { it.name == casting }
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(SCREEN_BUTTON_TAG)) {
            Icon(
                Icons.Outlined.DesktopWindows,
                contentDescription = stringResource(R.string.screen_menu),
                tint = if (onDark) Color.White else androidx.compose.material3.LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            targets.present.forEach { target ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screen_play_on, target.name)) },
                    onClick = {
                        open = false
                        controller.playOnScreen(target.id)
                    },
                )
            }
            if (onAScreen) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.screen_back_to_phone)) },
                    onClick = {
                        open = false
                        controller.playOnScreen(null)
                    },
                )
            }
        }
    }
}
