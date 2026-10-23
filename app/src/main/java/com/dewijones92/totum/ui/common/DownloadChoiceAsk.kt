package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.downloads.DownloadChooser
import com.dewijones92.totum.settings.DownloadChoice

const val DOWNLOAD_CHOICE_TAG = "download-choice"

@Composable
internal fun DownloadChoiceAsk(chooser: DownloadChooser) {
    var asking by remember { mutableStateOf<DownloadChooser.Ask?>(null) }
    var remember by remember { mutableStateOf(false) }
    LaunchedEffect(chooser) { chooser.asks.collect { asking = it } }
    val ask = asking ?: return
    val pick = { choice: DownloadChoice ->
        asking = null
        chooser.choose(ask, choice, remember)
    }
    AlertDialog(
        onDismissRequest = {
            Diag.log("download", "\"${ask.item.title}\": the download question was dismissed; nothing fetched")
            asking = null
        },
        modifier = Modifier.testTag(DOWNLOAD_CHOICE_TAG),
        title = { Text(stringResource(R.string.download_choice_title, ask.item.title)) },
        text = {
            Column {
                if (!ask.videoOnly) {
                    TextButton(onClick = { pick(DownloadChoice.Sound) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.download_choice_sound), modifier = Modifier.fillMaxWidth())
                    }
                }
                DownloadChoice.heights.forEach { height ->
                    TextButton(onClick = { pick(DownloadChoice.Video(height)) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            height?.let { stringResource(R.string.download_choice_video_height, it) }
                                ?: stringResource(R.string.download_choice_video_best),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                LabelledCheckbox(remember, stringResource(R.string.download_choice_remember)) { remember = it }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
internal fun rememberChooseDownload(): (MediaItem) -> Unit {
    val actions = LocalItemActions.current
    return remember(actions) {
        {
                item: MediaItem ->
            actions?.chooseDownload(item, item.pillar, askAnyway = false)
                ?: Diag.warn("download", "\"${item.title}\": no item actions here; nothing downloaded")
        }
    }
}
