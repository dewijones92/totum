package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.ui.common.homeStateText
import com.dewijones92.totum.ui.common.rowCard

class HomeSection(
    val available: Boolean = false,
    val jobs: List<HomeJob> = emptyList(),
    val onCancel: (MediaItemId) -> Unit = {},
    val onRetry: (MediaItemId) -> Unit = {},
)

internal fun LazyListScope.homeBody(home: HomeSection) {
    if (home.jobs.isEmpty()) sectionEmpty { stringResource(R.string.downloads_none_at_home) }
    items(home.jobs, key = { "home-${it.id}" }) { job ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .rowCard(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
                .testTag("$HOME_JOB_TAG-${job.id}"),
        ) {
            Column(Modifier.weight(1f)) {
                Text(job.item.title, style = MaterialTheme.typography.titleSmall)
                Text(homeStateText(job), style = MaterialTheme.typography.bodySmall)
                if (job.state == HomeJobState.DOWNLOADING) {
                    val total = job.totalBytes
                    if (total != null && total > 0) {
                        LinearProgressIndicator(
                            progress = { (job.bytes.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                    }
                }
            }
            if (job.state == HomeJobState.FAILED) {
                TextButton(
                    onClick = { home.onRetry(MediaItemId(job.id)) }
                ) { Text(stringResource(R.string.downloads_retry)) }
            }
            TextButton(onClick = { home.onCancel(MediaItemId(job.id)) }) {
                Text(stringResource(if (job.state == HomeJobState.DONE) R.string.home_delete else R.string.cancel))
            }
        }
    }
}

const val HOME_JOB_TAG = "home-job"
