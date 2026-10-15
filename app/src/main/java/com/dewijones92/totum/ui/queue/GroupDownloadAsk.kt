package com.dewijones92.totum.ui.queue

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.queue.GroupDownload
import com.dewijones92.totum.ui.common.HoldToConfirmDialog
import com.dewijones92.totum.ui.common.HoldToConfirmTexts

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
    HoldToConfirmDialog(
        texts = HoldToConfirmTexts(
            title = stringResource(R.string.group_download_title, title),
            body = pluralStringResource(R.plurals.group_download_body, count, count),
            yes = stringResource(R.string.group_download_yes),
            hint = stringResource(R.string.hold_to_download),
            no = stringResource(R.string.group_download_no),
        ),
        onYes = { choose(GroupDownload.YES, "held to download") },
        onNo = { choose(GroupDownload.NO, "said not now") },
        onDismiss = { choose(GroupDownload.NO, "dismissed the question") },
        yesTag = GROUP_DOWNLOAD_YES_TAG,
        noTag = GROUP_DOWNLOAD_NO_TAG,
    )
}

const val GROUP_DOWNLOAD_YES_TAG = "group-download-yes"
const val GROUP_DOWNLOAD_NO_TAG = "group-download-no"
