package com.dewijones92.totum.ui.report

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.MAX_SCREENSHOTS
import com.dewijones92.totum.diagnostics.NOTE_MAX_CHARS
import com.dewijones92.totum.ui.settings.ScreenshotPickerRow
import com.dewijones92.totum.ui.settings.rememberScreenshotPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val QUICK_REPORT_NOTE_TAG = "quick-report-note"
const val QUICK_REPORT_SEND_TAG = "quick-report-send"
const val QUICK_REPORT_SHEET_TAG = "quick-report-sheet"
const val QUICK_REPORT_THUMBNAIL_TAG = "quick-report-thumbnail"
private const val THUMBNAIL_LONGEST_SIDE = 320
private val THUMBNAIL_HEIGHT = 128.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuickReportSheet(
    captured: EncodedScreenshot?,
    onSend: (String, List<EncodedScreenshot>) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by rememberSaveable { mutableStateOf("") }
    val picks = rememberScreenshotPicker()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(QUICK_REPORT_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.quick_report_title), style = MaterialTheme.typography.titleLarge)
            Text(
                text = stringResource(R.string.quick_report_supporting),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it.take(NOTE_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(QUICK_REPORT_NOTE_TAG),
                placeholder = { Text(stringResource(R.string.settings_diagnostics_note_hint)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                ),
                minLines = 3,
                maxLines = 6,
            )
            val shots = (listOfNotNull(captured) + picks.ready).take(MAX_SCREENSHOTS)
            if (captured == null) {
                Text(
                    text = stringResource(R.string.quick_report_shot_missing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (shots.isNotEmpty()) Thumbnails(shots)
            ScreenshotPickerRow(picks)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = !picks.preparing,
                    onClick = { onSend(typed, picks.ready) },
                    modifier = Modifier.testTag(QUICK_REPORT_SEND_TAG),
                ) { Text(stringResource(R.string.quick_report_send)) }
            }
        }
    }
}

@Composable
private fun Thumbnails(shots: List<EncodedScreenshot>) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shots.forEachIndexed { index, shot ->
            val thumbnail by produceState<ImageBitmap?>(null, shot.base64.length, shot.name) {
                value = withContext(Dispatchers.Default) { thumbnailOf(shot) }
            }
            thumbnail?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.quick_report_preview_description, index + 1),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(THUMBNAIL_HEIGHT)
                        .clip(RoundedCornerShape(8.dp))
                        .testTag("$QUICK_REPORT_THUMBNAIL_TAG-$index"),
                )
            }
        }
    }
}

private fun thumbnailOf(shot: EncodedScreenshot): ImageBitmap? = runCatching {
    val bytes = Base64.decode(shot.base64, Base64.NO_WRAP)
    var sample = 1
    while (maxOf(shot.width, shot.height) / (sample * 2) >= THUMBNAIL_LONGEST_SIDE) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()
}.getOrElse { failure ->
    Diag.warn("diagnostics", "the preview of ${shot.name} could not be drawn", failure)
    null
}
