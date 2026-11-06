package com.dewijones92.totum.ui.queue

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.OfflineReadiness

/**
 * One line at the top of the queue answering "could I listen to this with no signal?".
 *
 * Dewi, 2026-08-02: *"I expect the gui / labels etc to be very very clear"*. Before this the
 * only sign of offline readiness was a small headphones glyph per row, so the question could
 * only be answered by scrolling 77 of them and counting — and the case that actually bit,
 * automatic downloads paused waiting for Wi-Fi, was invisible entirely.
 *
 * States are ordered by what a person needs to know first: blocked, then in progress, then
 * done. Saying "62 of 77 ready" while downloads are stalled would be true and useless.
 */
@Composable
internal fun OfflineSummary(
    readiness: OfflineReadiness,
    /** True when automatic downloads are switched off — then this line must not promise them. */
    autoDownloadOff: Boolean,
    /** True when downloads are waiting for Wi-Fi, which is otherwise completely silent. */
    waitingForWifi: Boolean,
    modifier: Modifier = Modifier,
    playable: OfflinePlayable? = null,
) {
    if (readiness.total == 0) return

    val (icon, text) = when {
        // Named first and plainly: this is the one state where nothing will improve on its own,
        // and the person has to do something about it.
        autoDownloadOff && readiness.waiting > 0 ->
            Icons.Filled.CloudOff to
                pluralStringResource(R.plurals.queue_offline_auto_off, readiness.waiting, readiness.waiting)

        waitingForWifi && readiness.waiting > 0 ->
            Icons.Filled.Wifi to
                pluralStringResource(R.plurals.queue_offline_waiting_wifi, readiness.waiting, readiness.waiting)

        readiness.downloading > 0 || readiness.waiting > 0 -> Icons.Filled.Downloading to pluralStringResource(
            R.plurals.queue_offline_in_progress,
            readiness.downloading + readiness.waiting,
            readiness.ready,
            readiness.total,
            readiness.downloading + readiness.waiting,
        )

        // Downloadable, but only if asked — a film, which nothing fetches on your behalf. Said
        // before the "can't be downloaded" line because the two are different answers and this one
        // has something the person can do about it.
        readiness.notAutomatic > 0 -> Icons.Filled.OfflinePin to pluralStringResource(
            R.plurals.queue_offline_manual_only,
            readiness.notAutomatic,
            readiness.ready,
            readiness.notAutomatic,
        )

        // Settled. The count of things that can never be offline is worth stating even in the
        // good case, so "62 of 66" never looks like something silently went missing.
        readiness.unavailableOffline > 0 -> Icons.Filled.OfflinePin to pluralStringResource(
            R.plurals.queue_offline_ready_with_gaps,
            readiness.unavailableOffline,
            readiness.ready,
            readiness.unavailableOffline,
        )

        else ->
            Icons.Filled.OfflinePin to
                pluralStringResource(R.plurals.queue_offline_ready_all, readiness.ready, readiness.ready)
    }

    Banner(icon = icon, text = text, playable = playable, modifier = modifier)
}

internal data class OfflinePlayable(
    val count: Int,
    val offline: Boolean,
    val onlyThose: Boolean,
    val onToggle: () -> Unit,
)

@Composable
private fun Banner(icon: ImageVector, text: String, playable: OfflinePlayable?, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    // The text says everything; the icon is decoration beside it, and announcing
                    // both would read the state twice to a screen reader.
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
            playable?.let { PlayableChip(it) }
        }
    }
}

@Composable
private fun PlayableChip(playable: OfflinePlayable) {
    FilterChip(
        selected = playable.onlyThose,
        onClick = playable.onToggle,
        label = {
            Text(
                pluralStringResource(
                    if (playable.offline) R.plurals.queue_playable_now else R.plurals.queue_playable_with_no_signal,
                    playable.count,
                    playable.count,
                ),
            )
        },
        modifier = Modifier.padding(top = 4.dp).testTag(QUEUE_PLAYABLE_CHIP_TAG),
    )
}

internal const val QUEUE_PLAYABLE_CHIP_TAG = "queue-playable-chip"
