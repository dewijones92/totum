package com.dewijones92.totum.ui.queue

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.queue.GroupDownload

@Composable
fun GroupDownloadAsk(container: AppContainer) {
    val settings by container.appPreferences.settings.collectAsStateWithLifecycle()
    val choices by container.groupDownloads.choices.collectAsStateWithLifecycle()
    val queue by container.playbackQueue.state.collectAsStateWithLifecycle()
    if (!settings.autoDownloadQueue) return
    val asking = choices.entries.firstOrNull { it.value == GroupDownload.ASK }?.key ?: return
    val members = queue.entries.filter { it.group?.id == asking }
    val title = members.firstOrNull()?.group?.title ?: return
    val count = members.size
    val choose = { choice: GroupDownload, why: String -> container.groupDownloads.choose(asking, choice, why) }
    AlertDialog(
        onDismissRequest = { choose(GroupDownload.NO, "dismissed the question") },
        title = { Text(stringResource(R.string.group_download_title, title)) },
        text = { Text(pluralStringResource(R.plurals.group_download_body, count, count)) },
        confirmButton = {
            TextButton(
                onClick = { choose(GroupDownload.YES, "said download") },
                modifier = Modifier.testTag(GROUP_DOWNLOAD_YES_TAG),
            ) { Text(stringResource(R.string.group_download_yes)) }
        },
        dismissButton = {
            TextButton(
                onClick = { choose(GroupDownload.NO, "said not now") },
                modifier = Modifier.testTag(GROUP_DOWNLOAD_NO_TAG),
            ) { Text(stringResource(R.string.group_download_no)) }
        },
    )
}

const val GROUP_DOWNLOAD_YES_TAG = "group-download-yes"
const val GROUP_DOWNLOAD_NO_TAG = "group-download-no"
