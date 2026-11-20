package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.showing
import com.dewijones92.totum.phonemedia.ScreenPictures
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.ScreenTarget
import com.dewijones92.totum.playback.ScreenTargets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

internal class PhotoScreens(
    private val controller: PlaybackController,
    private val pictures: ScreenPictures,
    private val operations: Operations?,
) {
    val targets: StateFlow<ScreenTargets> get() = controller.screens

    suspend fun show(screen: ScreenTarget, entry: PhoneMediaEntry, label: String) {
        operations.showing("show-picture-on-screen", label) {
            val file = withContext(Dispatchers.IO) { pictures.forScreen(entry) }
            checkNotNull(file) { "${entry.title} could not be prepared for ${screen.name}" }
            Diag.log("phone-media", "dewidebug showing ${entry.id.value} on screen ${screen.name} (${screen.id})")
            controller.showPictureOnScreen(screen.id, file.path, entry.title)
        }
    }

    fun stop(screen: ScreenTarget) {
        Diag.log("phone-media", "dewidebug stopped showing photos on screen ${screen.name} (${screen.id})")
        controller.clearScreen(screen.id)
    }
}

internal data class ScreenMirror(
    val targets: ScreenTargets,
    val showingOn: ScreenTarget?,
    val choose: (ScreenTarget?) -> Unit,
)

@Composable
internal fun rememberScreenMirror(
    screens: PhotoScreens,
    photos: List<PhoneMediaEntry>,
    pager: PagerState,
): ScreenMirror {
    val resources = LocalResources.current
    val targets by screens.targets.collectAsStateWithLifecycle()
    var showingId by rememberSaveable { mutableStateOf<String?>(null) }
    val showingOn = targets.present.firstOrNull { it.id == showingId }
    LaunchedEffect(showingId, showingOn == null) {
        if (showingId != null && showingOn == null) {
            Diag.log("phone-media", "dewidebug screen $showingId is no longer present; photos stop going to it")
            showingId = null
        }
    }
    LaunchedEffect(showingOn?.id, pager) {
        val screen = showingOn ?: return@LaunchedEffect
        snapshotFlow { pager.currentPage }.collectLatest { page ->
            val entry = photos.getOrNull(page) ?: return@collectLatest
            screens.show(screen, entry, resources.getString(R.string.photo_screen_working, entry.title, screen.name))
        }
    }
    return ScreenMirror(targets, showingOn) { chosen ->
        showingOn?.takeIf { chosen == null }?.let(screens::stop)
        showingId = chosen?.id
        Diag.log("phone-media", "dewidebug photos go to screen ${chosen?.name ?: "none"} (${chosen?.id})")
    }
}

@Composable
internal fun ScreenRow(mirror: ScreenMirror) {
    val (targets, showingOn, onChoose) = mirror
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            showingOn != null -> TextButton(
                onClick = { onChoose(null) },
                modifier = Modifier.testTag(PHOTO_SCREEN_STOP_TAG),
            ) { Text(stringResource(R.string.photo_screen_stop, showingOn.name), color = Color.White) }
            targets.present.isEmpty() -> Text(
                stringResource(R.string.photo_screen_none),
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag(PHOTO_SCREEN_NONE_TAG),
            )
            else -> Box {
                TextButton(onClick = { open = true }, modifier = Modifier.testTag(PHOTO_SCREEN_BUTTON_TAG)) {
                    Text(stringResource(R.string.photo_screen_show), color = Color.White)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    targets.present.forEach { target ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.photo_screen_pick, target.name)) },
                            onClick = {
                                open = false
                                onChoose(target)
                            },
                            modifier = Modifier.testTag("$PHOTO_SCREEN_ITEM_TAG-${target.id}"),
                        )
                    }
                }
            }
        }
    }
}

const val PHOTO_SCREEN_BUTTON_TAG = "phone-media-photo-screen"
const val PHOTO_SCREEN_STOP_TAG = "phone-media-photo-screen-stop"
const val PHOTO_SCREEN_NONE_TAG = "phone-media-photo-screen-none"
const val PHOTO_SCREEN_ITEM_TAG = "phone-media-photo-screen-item"
