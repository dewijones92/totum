package com.dewijones92.totum.ui.subscriptions

import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.data.channel.ChannelCheckProgress
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PublishedAge
import com.dewijones92.totum.domain.SourceActivity
import com.dewijones92.totum.domain.pillar
import com.dewijones92.totum.domain.searchableText
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.common.BulkAction
import com.dewijones92.totum.ui.common.EmptyState
import com.dewijones92.totum.ui.common.FactEmoji
import com.dewijones92.totum.ui.common.FilterToggle
import com.dewijones92.totum.ui.common.FilterableList
import com.dewijones92.totum.ui.common.LocalNow
import com.dewijones92.totum.ui.common.LocalOpenSource
import com.dewijones92.totum.ui.common.SelectableList
import com.dewijones92.totum.ui.common.SelectionCheckbox
import com.dewijones92.totum.ui.common.SourceArtwork
import com.dewijones92.totum.ui.common.isSelecting
import com.dewijones92.totum.ui.common.orSelected
import com.dewijones92.totum.ui.common.pillarRowTint
import com.dewijones92.totum.ui.common.rememberListFilter
import com.dewijones92.totum.ui.common.rememberSelection
import com.dewijones92.totum.ui.common.rowCard
import com.dewijones92.totum.ui.common.selectableClicks

@Composable
fun AllSubscriptionsScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AllSubscriptionsViewModel = viewModel(factory = AllSubscriptionsViewModel.factory(container))
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.checkChannels() }
    AllSubscriptionsContent(
        sources = sources,
        onBack = onBack,
        modifier = modifier,
        checking = checking,
        onRefresh = viewModel::refresh,
        onUnsubscribe = viewModel::unsubscribe,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AllSubscriptionsContent(
    sources: List<SourceActivity>?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    checking: ChannelCheckProgress? = null,
    onRefresh: () -> Unit = {},
    onUnsubscribe: (List<MediaSource>) -> Unit = {},
    onOpen: ((SourceActivity) -> Unit)? = LocalOpenSource.current?.let { open -> { open(it.source) } },
) {
    val selection = rememberSelection("subscriptions")
    Surface(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            val listFilter = rememberListFilter("subscriptions")
            BackHeader(stringResource(R.string.all_subscriptions_title), onBack) {
                FilterToggle(listFilter, sources?.size ?: 0)
            }
            checking?.let { CheckingChannels(it) }
            PullToRefreshBox(isRefreshing = false, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
                if (sources.isNullOrEmpty()) {
                    SubscriptionsBody(sources, onOpen)
                } else {
                    FilterableList(
                        "subscriptions",
                        sources,
                        { it.source.searchableText },
                        filter = listFilter
                    ) { shown, _ ->
                        SelectableList(
                            selection,
                            sources,
                            shown,
                            { it.source.id.value },
                            { chosen ->
                                listOf(
                                    BulkAction(R.string.channel_unsubscribe, confirm = R.plurals.unsubscribe_confirm) {
                                        onUnsubscribe(chosen.map { it.source })
                                    },
                                )
                            },
                        ) { SubscriptionsBody(shown, onOpen) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckingChannels(progress: ChannelCheckProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.all_subscriptions_checking, progress.checked, progress.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.checked.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun SubscriptionsBody(sources: List<SourceActivity>?, onOpen: ((SourceActivity) -> Unit)?) {
    when {
        sources == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        sources.isEmpty() -> EmptyState(
            icon = Icons.Outlined.Subscriptions,
            headline = stringResource(R.string.all_subscriptions_empty_headline),
            supportingText = stringResource(R.string.all_subscriptions_empty_supporting),
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(sources, key = { it.source.id.value }) { activity ->
                SubscriptionRow(activity, onOpen)
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
            .rowCard(pillarRowTint(pillar).orSelected(source.id.value), MaterialTheme.shapes.large)
            .selectableClicks(
                source.id.value,
                enabled = onOpen != null,
                onClick = { onOpen?.invoke(activity) },
                onLongClick = null
            )
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
        if (isSelecting()) SelectionCheckbox(source.id.value, source.title)
    }
}

private val ARTWORK_SIZE = 48.dp
