package com.dewijones92.totum.ui.subscriptions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.LocalOpenSource
import com.dewijones92.totum.ui.common.SourceArtwork
import com.dewijones92.totum.ui.common.pillarRowTint

@Composable
fun AllSubscriptionsScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AllSubscriptionsViewModel = viewModel(factory = AllSubscriptionsViewModel.factory(container))
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    AllSubscriptionsContent(sources, onBack, modifier)
}

@Composable
internal fun AllSubscriptionsContent(
    sources: List<SourceActivity>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpen: ((SourceActivity) -> Unit)? = LocalOpenSource.current?.let { open -> { open(it.source) } },
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BackHeader(stringResource(R.string.all_subscriptions_title), onBack)
            if (sources.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Subscriptions,
                    headline = stringResource(R.string.all_subscriptions_empty_headline),
                    supportingText = stringResource(R.string.all_subscriptions_empty_supporting),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(sources, key = { it.source.id.value }) { activity ->
                        SubscriptionRow(activity, onOpen)
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionRow(activity: SourceActivity, onOpen: ((SourceActivity) -> Unit)?) {
    val source = activity.source
    val pillar = source.pillar
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(pillarRowTint(pillar))
            .clickable(enabled = onOpen != null) { onOpen?.invoke(activity) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SourceArtwork(source.artworkUrl, pillar, Modifier.size(ARTWORK_SIZE))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(source.title, style = MaterialTheme.typography.titleMedium)
            val maker = if (pillar == MediaKind.PODCAST) FactEmoji.PODCAST else FactEmoji.CHANNEL
            val latest = activity.latest
            val line = latest?.publishedAt?.let { at ->
                "${FactEmoji.PUBLISHED} ${PublishedAge.text(at, LocalNow.current)} · ${latest.title}"
            } ?: stringResource(R.string.all_subscriptions_no_recent)
            Text(
                "$maker $line",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val ARTWORK_SIZE = 48.dp
