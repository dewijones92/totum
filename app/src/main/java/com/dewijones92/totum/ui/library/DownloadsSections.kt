package com.dewijones92.totum.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.downloads.DownloadsSection
import com.dewijones92.totum.ui.common.MediaThumbnail
import com.dewijones92.totum.ui.common.rowCard

class Sections(
    val current: DownloadsSection? = null,
    val onSection: (DownloadsSection) -> Unit = {},
    val onRetryAll: () -> Unit = {},
    val onClearFailed: () -> Unit = {},
)

internal fun LazyListScope.waitingSection(waiting: List<LibraryViewModel.InProgress>, onCancel: (MediaItemId) -> Unit) {
    if (waiting.isEmpty()) return
    item { SectionTitle(stringResource(R.string.downloads_waiting_section, waiting.size)) }
    items(waiting, key = { "waiting-${it.id.value}" }) { entry ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .rowCard(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Column(Modifier.weight(1f)) { DownloadCardHeader(entry.item) }
            TextButton(onClick = { onCancel(entry.id) }) { Text(stringResource(R.string.cancel)) }
        }
    }
}

internal fun LazyListScope.inProgressBody(
    inProgress: List<LibraryViewModel.InProgress>,
    retrying: List<LibraryViewModel.Failed>,
    onCancel: (MediaItemId) -> Unit,
    onCancelAll: () -> Unit,
    onRetry: (MediaItemId) -> Unit,
) {
    val (waiting, running) = inProgress.partition { it.isWaiting() }
    runningSection(running, onCancel, onCancelAll)
    waitingSection(waiting, onCancel)
    retryingSection(retrying, System.currentTimeMillis()) { onRetry(it.id) }
    if (inProgress.isEmpty() && retrying.isEmpty()) sectionEmpty { stringResource(R.string.downloads_none_running) }
}

internal fun LazyListScope.failedBody(
    stopped: List<LibraryViewModel.Failed>,
    sections: Sections,
    onRetry: (MediaItemId) -> Unit,
    onDismiss: (MediaItemId) -> Unit,
) {
    failedActions(stopped.size, sections.onRetryAll, sections.onClearFailed)
    failedSection(stopped, onRetry, onDismiss)
    if (stopped.isEmpty()) sectionEmpty { stringResource(R.string.downloads_none_failed) }
}

class Handlers(
    val onCancel: (MediaItemId) -> Unit,
    val onCancelAll: () -> Unit,
    val onRetry: (MediaItemId) -> Unit,
    val onDismiss: (MediaItemId) -> Unit,
)

internal fun LazyListScope.activeBody(
    current: DownloadsSection,
    inProgress: List<LibraryViewModel.InProgress>,
    failed: List<LibraryViewModel.Failed>,
    sections: Sections,
    handlers: Handlers,
) {
    val (retrying, stopped) = failed.partition { it.retry != null }
    when (current) {
        DownloadsSection.IN_PROGRESS ->
            inProgressBody(inProgress, retrying, handlers.onCancel, handlers.onCancelAll, handlers.onRetry)
        DownloadsSection.FAILED -> failedBody(stopped, sections, handlers.onRetry, handlers.onDismiss)
        DownloadsSection.ON_THE_PHONE -> Unit
    }
}

internal fun defaultSection(running: Int, failed: Int, onThePhone: Int): DownloadsSection = when {
    running > 0 -> DownloadsSection.IN_PROGRESS
    onThePhone > 0 -> DownloadsSection.ON_THE_PHONE
    failed > 0 -> DownloadsSection.FAILED
    else -> DownloadsSection.ON_THE_PHONE
}

internal fun LibraryViewModel.InProgress.isWaiting(): Boolean =
    state.downloadedBytes == 0L && state.totalBytes == null

@Composable
internal fun SectionChips(
    current: DownloadsSection,
    running: Int,
    failed: Int,
    onThePhone: Int,
    onSection: (DownloadsSection) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(DOWNLOADS_SECTIONS_TAG),
    ) {
        listOf(
            DownloadsSection.IN_PROGRESS to stringResource(R.string.downloads_section_in_progress, running),
            DownloadsSection.FAILED to stringResource(R.string.downloads_section_failed, failed),
            DownloadsSection.ON_THE_PHONE to stringResource(R.string.downloads_section_on_the_phone, onThePhone),
        ).forEach { (section, label) ->
            FilterChip(
                selected = section == current,
                onClick = { onSection(section) },
                label = { Text(label) },
                modifier = Modifier.testTag("$DOWNLOADS_SECTIONS_TAG-${section.name.lowercase()}"),
            )
        }
    }
}

internal fun LazyListScope.retryingSection(
    retrying: List<LibraryViewModel.Failed>,
    nowMs: Long,
    onRetryNow: (LibraryViewModel.Failed) -> Unit,
) {
    if (retrying.isEmpty()) return
    item { SectionTitle(stringResource(R.string.downloads_retrying_section, retrying.size)) }
    items(retrying, key = { "retrying-${it.id.value}" }) { entry ->
        val plan = entry.retry ?: return@items
        val inSeconds = ((plan.atMs - nowMs) / MS_PER_S).coerceAtLeast(0)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .rowCard(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.large)
                .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        ) {
            DownloadCardHeader(entry.item)
            Text(
                text = stringResource(
                    R.string.downloads_retrying_row,
                    plan.attempt,
                    plan.of,
                    waitText(inSeconds),
                    entry.reason
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            TextButton(onClick = { onRetryNow(entry) }) { Text(stringResource(R.string.downloads_retry_now)) }
        }
    }
}

internal fun LazyListScope.failedActions(count: Int, onRetryAll: () -> Unit, onClearFailed: () -> Unit) {
    if (count == 0) return
    item {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        ) {
            TextButton(onClick = onRetryAll, modifier = Modifier.testTag(DOWNLOADS_RETRY_ALL_TAG)) {
                Text(stringResource(R.string.downloads_retry_all, count))
            }
            TextButton(onClick = onClearFailed, modifier = Modifier.testTag(DOWNLOADS_CLEAR_FAILED_TAG)) {
                Text(stringResource(R.string.downloads_clear_failed))
            }
        }
    }
}

internal fun LazyListScope.sectionEmpty(text: @Composable () -> String) {
    item {
        Text(
            text = text(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun waitText(seconds: Long): String = if (seconds < SECONDS_A_MINUTE) {
    pluralStringResource(R.plurals.downloads_wait_seconds, seconds.toInt(), seconds)
} else {
    val minutes = (seconds + SECONDS_A_MINUTE - 1) / SECONDS_A_MINUTE
    pluralStringResource(R.plurals.downloads_wait_minutes, minutes.toInt(), minutes)
}

internal const val DOWNLOADS_SECTIONS_TAG = "downloads-sections"
internal const val DOWNLOADS_RETRY_ALL_TAG = "downloads-retry-all"
internal const val DOWNLOADS_CLEAR_FAILED_TAG = "downloads-clear-failed"
private const val MS_PER_S = 1_000L
private const val SECONDS_A_MINUTE = 60L

@Composable
internal fun DownloadCardHeader(item: MediaItem, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        MediaThumbnail(
            url = item.thumbnailUrl,
            contentDescription = null,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.size(DOWNLOAD_THUMB_WIDTH, DOWNLOAD_THUMB_HEIGHT),
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            item.author?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

private val DOWNLOAD_THUMB_WIDTH = 64.dp
private val DOWNLOAD_THUMB_HEIGHT = 36.dp
