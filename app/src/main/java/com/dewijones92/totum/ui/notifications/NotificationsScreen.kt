package com.dewijones92.totum.ui.notifications

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.ui.common.FilterableList
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.LocalPlayStates
import com.dewijones92.totum.ui.common.MediaItemRow
import com.dewijones92.totum.ui.common.SelectableMediaList
import com.dewijones92.totum.ui.common.mediaItemFacts
import com.dewijones92.totum.ui.common.rememberSelection
import com.dewijones92.totum.ui.common.rowTint

/**
 * New uploads from your subscriptions since you last looked — the stand-in for
 * YouTube's notification bell. Shows a snapshot of what was new on open, then
 * marks everything seen so the badge clears (the list itself stays put).
 */
@Composable
fun NotificationsScreen(
    viewModel: NotificationsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Snapshot the new uploads on open; marking them seen clears the badge but
    // must not empty the list the user is looking at.
    val uploads = remember { viewModel.snapshotUploads() }
    LaunchedEffect(Unit) { viewModel.markAllSeen() }

    Surface(modifier = modifier.fillMaxSize()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(
                    text = stringResource(R.string.notifications_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            val selection = rememberSelection("notifications")
            if (uploads.isEmpty()) {
                Text(
                    text = stringResource(R.string.notifications_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            } else {
                FilterableList("notifications", uploads, { it.item.searchableText }) { shown, _ ->
                    SelectableMediaList("notifications", uploads, shown, { it.item }, hoisted = selection) {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            itemsIndexed(shown, key = { _, upload -> upload.item.id.value }) { index, upload ->
                                // One divider between the unread run and the rest, so the boundary is
                                // visible without a header shouting at you.
                                if (index > 0 && !upload.unread && shown[index - 1].unread) {
                                    SeenSince()
                                }
                                MediaItemRow(
                                    item = upload.item,
                                    subtitleLines = mediaItemFacts(upload.item, MediaKind.VIDEO, LocalNow.current),
                                    pillar = MediaKind.VIDEO,
                                    onPlay = { viewModel.play(upload.item) },
                                    // Through the row's own tint rather than a background of our own:
                                    // two backgrounds composite, and a played unread row wore both.
                                    tint = rowTint(
                                        pillar = MediaKind.VIDEO,
                                        playState = LocalPlayStates.current[upload.item.id] ?: PlayState.Unplayed,
                                        unread = upload.unread,
                                    ),
                                )
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Marks where the new ones end and the ones you have already seen begin. */
@Composable
private fun SeenSince() {
    Text(
        text = stringResource(R.string.notifications_seen_already),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}
