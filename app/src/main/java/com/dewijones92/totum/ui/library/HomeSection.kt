package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.ui.common.HomeRow
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.downloadStateOf
import com.dewijones92.totum.ui.common.homeStateText
import com.dewijones92.totum.ui.common.rowFacts
import com.dewijones92.totum.ui.common.LocalItemShield
import com.dewijones92.totum.data.backup.titleOf

class HomeSection(
    val available: Boolean = false,
    val jobs: List<HomeJob> = emptyList(),
    val onCancel: (MediaItemId) -> Unit = {},
    val onRetry: (MediaItemId) -> Unit = {},
    val onCopy: (MediaItemId) -> Unit = {},
    val onPlay: (PlayableItem) -> Unit = {},
    val storage: StorageUsage? = null,
)

internal fun LazyListScope.homeBody(home: HomeSection) {
    home.storage?.let { storage ->
        item(key = "home-storage") { StorageSummary(storage, R.string.home_storage, R.string.home_storage_with_free) }
    }
    if (home.jobs.isEmpty()) sectionEmpty { stringResource(R.string.downloads_none_at_home) }
    items(home.jobs, key = { "home-${it.id}" }) { job ->
        val id = MediaItemId(job.id)
        val playable = job.item.toPlayable()
        val local = downloadStateOf(id)
        val onPhone = local is DownloadState.Downloaded
        Column(Modifier.fillMaxWidth().testTag("$HOME_JOB_TAG-${job.id}")) {
            if (playable != null) {
                MediaItemRow(
                    item = playable.item,
                    subtitleLines = rowFacts(playable.item, playable.pillar),
                    pillar = playable.pillar,
                    onPlay = { home.onPlay(playable) },
                )
            } else {
                Text(LocalItemShield.current.titleOf(job.item), Modifier.padding(horizontal = 16.dp))
                Text(homeStateText(job, onPhone), Modifier.padding(horizontal = 16.dp))
            }
            HomeProgress(job)
            HomeButtons(job, local, home)
        }
    }
}

@androidx.compose.runtime.Composable
private fun HomeProgress(job: HomeJob) {
    if (job.state != HomeJobState.DOWNLOADING) return
    val total = job.totalBytes
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)
    if (total != null && total > 0) {
        LinearProgressIndicator(progress = { (job.bytes.toFloat() / total).coerceIn(0f, 1f) }, modifier = modifier)
    } else {
        LinearProgressIndicator(modifier)
    }
}

@androidx.compose.runtime.Composable
private fun HomeButtons(job: HomeJob, local: DownloadState, home: HomeSection) {
    val id = MediaItemId(job.id)
    val offers = HomeRow.offers(job, local)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        if (offers.copyNow) {
            TextButton(onClick = { home.onCopy(id) }, Modifier.testTag("$HOME_COPY_TAG-${job.id}")) {
                Text(stringResource(R.string.home_copy_now))
            }
        }
        if (offers.retry) TextButton(onClick = { home.onRetry(id) }) { Text(stringResource(R.string.downloads_retry)) }
        TextButton(onClick = { home.onCancel(id) }) { Text(stringResource(offers.remove)) }
    }
}

const val HOME_JOB_TAG = "home-job"
const val HOME_COPY_TAG = "home-copy"
