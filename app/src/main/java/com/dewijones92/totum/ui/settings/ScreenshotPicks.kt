package com.dewijones92.totum.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.MAX_SCREENSHOTS
import com.dewijones92.totum.diagnostics.encodeScreenshot
import kotlinx.coroutines.launch

@Stable
class ScreenshotPicks {
    var ready: List<EncodedScreenshot> by mutableStateOf(emptyList())
    var preparing: Boolean by mutableStateOf(false)
    var unreadable: Int by mutableStateOf(0)
    internal var pick: () -> Unit = {}
}

@Composable
fun rememberScreenshotPicker(): ScreenshotPicks {
    val picks = remember { ScreenshotPicks() }
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_SCREENSHOTS),
    ) { uris ->
        Diag.log("diagnostics", "${uris.size} screenshot(s) picked")
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        picks.preparing = true
        scope.launch {
            val encoded = uris.mapIndexed { index, uri -> encodeScreenshot(resolver, uri, index) }
            picks.ready = encoded.filterNotNull()
            picks.unreadable = encoded.count { it == null }
            picks.preparing = false
        }
    }
    picks.pick = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    return picks
}

@Composable
fun ScreenshotPickerRow(picks: ScreenshotPicks) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        TextButton(
            onClick = { picks.pick() },
            enabled = !picks.preparing,
            modifier = Modifier.testTag(ADD_SCREENSHOTS_TAG)
        ) {
            Text(stringResource(R.string.settings_diagnostics_add_screenshots))
        }
        val status = when {
            picks.preparing -> stringResource(R.string.settings_diagnostics_screenshots_preparing)
            picks.unreadable > 0 -> pluralStringResource(
                R.plurals.settings_diagnostics_screenshots_partly,
                picks.ready.size,
                picks.ready.size,
                picks.unreadable,
            )
            picks.ready.isNotEmpty() -> pluralStringResource(
                R.plurals.settings_diagnostics_screenshots_ready,
                picks.ready.size,
                picks.ready.size,
            )
            else -> null
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

const val ADD_SCREENSHOTS_TAG = "diagnostics-add-screenshots"
