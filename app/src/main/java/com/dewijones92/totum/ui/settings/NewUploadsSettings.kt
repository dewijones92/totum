package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.data.podcast.subscribedSources
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.uploads.inboxKeyOf

@Composable
internal fun NewUploadsSettings(container: AppContainer, settings: AppPreferences.Settings) {
    val prefs = container.appPreferences
    var choosing by rememberSaveable { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.settings_inbox_section))
    SwitchRow(
        label = stringResource(R.string.settings_inbox_all),
        summary = stringResource(R.string.settings_inbox_all_summary),
        checked = settings.inboxAll,
        onCheckedChange = prefs::setInboxAll,
    )
    if (!settings.inboxAll) {
        NavRow(
            label = stringResource(R.string.settings_inbox_sources) + " (${settings.inboxSources.size})",
            onClick = { choosing = true },
        )
    }
    if (choosing) InboxSourcesSheet(container, settings, onDismiss = { choosing = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InboxSourcesSheet(container: AppContainer, settings: AppPreferences.Settings, onDismiss: () -> Unit) {
    val sources by remember(container) {
        subscribedSources(container.podcastRepository, container.accountSubscriptions.channels)
    }.collectAsStateWithLifecycle(emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            SectionTitle(stringResource(R.string.settings_inbox_sources))
            LazyColumn {
                items(sources.sortedBy { it.title.lowercase() }, key = { it.id.value }) { source ->
                    SwitchRow(
                        label = source.title,
                        summary = stringResource(
                            if (source is MediaSource.PodcastFeed) {
                                R.string.settings_inbox_podcast
                            } else {
                                R.string.settings_inbox_channel
                            },
                        ),
                        checked = inboxKeyOf(source) in settings.inboxSources ||
                            source.id.value in settings.inboxSources,
                        onCheckedChange = { on ->
                            container.appPreferences.setInboxSource(SourceId(inboxKeyOf(source)), on)
                            if (!on) container.appPreferences.setInboxSource(source.id, false)
                        },
                    )
                }
            }
            if (sources.isEmpty()) Text(stringResource(R.string.settings_inbox_none), Modifier.padding(16.dp))
        }
    }
}
