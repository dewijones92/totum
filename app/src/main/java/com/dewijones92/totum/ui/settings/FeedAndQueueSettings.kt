package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.settings.AppPreferences

internal val EXPLORE_CAPS = listOf(0, 3, 5, 10)
internal const val EXPLORE_CAP_TAG = "exploreCap"

@Composable
internal fun FeedAndQueueSettings(settings: AppPreferences.Settings, prefs: AppPreferences) {
    SectionTitle(stringResource(R.string.settings_feed_section))
    SwitchRow(
        label = stringResource(R.string.settings_feed_hidden),
        summary = stringResource(R.string.settings_feed_hidden_summary),
        checked = settings.feedHiddenUntilAsked,
        onCheckedChange = prefs::setFeedHiddenUntilAsked,
    )
    SectionTitle(stringResource(R.string.settings_queue_section))
    SwitchRow(
        label = stringResource(R.string.settings_rewind),
        summary = stringResource(R.string.settings_rewind_summary),
        checked = settings.rewindOnResume,
        onCheckedChange = prefs::setRewindOnResume,
    )
    SwitchRow(
        label = stringResource(R.string.settings_silent_tail),
        summary = stringResource(R.string.settings_silent_tail_summary),
        checked = settings.silentTailEnds,
        onCheckedChange = prefs::setSilentTailEnds,
    )
    SwitchRow(
        label = stringResource(R.string.settings_gapless),
        summary = stringResource(R.string.settings_gapless_summary),
        checked = settings.gaplessQueue,
        onCheckedChange = prefs::setGaplessQueue,
    )
    SwitchRow(
        label = stringResource(R.string.settings_explore),
        summary = stringResource(R.string.settings_explore_summary),
        checked = settings.exploreByDefault,
        onCheckedChange = prefs::setExploreByDefault,
    )
    ExploreCapRow(settings.exploreCap, prefs::setExploreCap)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExploreCapRow(cap: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.settings_explore_cap), style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_explore_cap_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EXPLORE_CAPS.forEach { choice ->
                FilterChip(
                    selected = choice == cap,
                    onClick = { onSelect(choice) },
                    label = {
                        Text(if (choice == 0) stringResource(R.string.settings_explore_cap_none) else choice.toString())
                    },
                    modifier = Modifier.testTag("$EXPLORE_CAP_TAG:$choice"),
                )
            }
        }
    }
}
