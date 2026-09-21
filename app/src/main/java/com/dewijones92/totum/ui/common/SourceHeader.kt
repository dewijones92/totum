package com.dewijones92.totum.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R

/**
 * The header of a source's page — back, title, subscribe toggle. One component
 * for both pillars: a video channel and a podcast feed are the same thing here
 * (a [com.dewijones92.totum.domain.MediaSource] you can follow), so their pages
 * share this rather than each growing their own.
 */
@Composable
fun SourceHeader(
    title: String,
    subscribed: Boolean,
    onBack: () -> Unit,
    onToggleSubscribed: () -> Unit,
    /** Opens the group checklist; omitted where grouping does not apply. */
    onOpenGroups: (() -> Unit)? = null,
    /**
     * The network behind a show, under its name — labelled by the same seam every row uses, so the
     * page cannot disagree with the episodes listed beneath it.
     *
     * A page whose whole job is to say what this show IS was naming the show and nothing else,
     * while every row under it carried the network (Dewi's "wherever they SHOULD be", 2026-09-21).
     * Null for a channel, which has no second name.
     */
    publisher: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleLarge)
            mediaFacts(author = title, publisher = publisher, dateText = null)
                .drop(1)
                .forEach { fact ->
                    Text(
                        text = fact,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
        }
        onOpenGroups?.let { open ->
            IconButton(onClick = open) {
                Icon(Icons.Outlined.Bookmarks, contentDescription = stringResource(R.string.groups_add_to))
            }
        }
        if (subscribed) {
            OutlinedButton(onClick = onToggleSubscribed) {
                Text(stringResource(R.string.channel_unsubscribe))
            }
        } else {
            FilledTonalButton(onClick = onToggleSubscribed) {
                Text(stringResource(R.string.channel_subscribe))
            }
        }
    }
}
