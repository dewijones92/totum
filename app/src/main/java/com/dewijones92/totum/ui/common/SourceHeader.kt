package com.dewijones92.totum.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaKind

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
    /**
     * Which maker this page is about — a mic for a show, a screen for a channel.
     *
     * Passed rather than assumed, because this header serves BOTH pillars (`PodcastFeedScreen` and
     * `ChannelScreen`) and it hardcoded the podcast glyph. Unobservable today, since the line it
     * labels is filtered back out and a channel has no publisher — but a label that is wrong for
     * half its callers is not something to leave sitting under a filter that depends on it.
     */
    pillar: MediaKind = MediaKind.PODCAST,
    artworkUrl: HttpUrl? = null,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(pillarRowTint(pillar), Color.Transparent)))
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            BackButton(onBack)
            Spacer(Modifier.weight(1f))
            actions()
            onOpenGroups?.let { open ->
                IconButton(onClick = open) {
                    Icon(Icons.Outlined.Bookmarks, contentDescription = stringResource(R.string.groups_add_to))
                }
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 4.dp, top = 12.dp),
        ) {
            SourceArtwork(artworkUrl, pillar, Modifier.size(HERO_ARTWORK))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                // Labelled 🎙️ and filtered by that label, not by position: this is a podcast page, and
                // the default 📺 would only ever have shown if the positional drop mis-fired — so the
                // failure mode was "a podcast page wearing a television".
                val makerEmoji = if (pillar == MediaKind.PODCAST) FactEmoji.PODCAST else FactEmoji.CHANNEL
                mediaFacts(author = title, publisher = publisher, dateText = null, authorEmoji = makerEmoji)
                    .filterNot { it.startsWith(makerEmoji) }
                    .forEach { fact ->
                        Text(
                            text = fact,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
            }
        }
        val buttonModifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
        if (subscribed) {
            OutlinedButton(onClick = onToggleSubscribed, modifier = buttonModifier) {
                Text(stringResource(R.string.channel_unsubscribe))
            }
        } else {
            Button(onClick = onToggleSubscribed, modifier = buttonModifier) {
                Text(stringResource(R.string.channel_subscribe))
            }
        }
    }
}

private val HERO_ARTWORK = 88.dp
