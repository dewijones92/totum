package com.dewijones92.totum.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dewijones92.totum.R
import com.dewijones92.totum.settings.AppPreferences

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
        label = stringResource(R.string.settings_gapless),
        summary = stringResource(R.string.settings_gapless_summary),
        checked = settings.gaplessQueue,
        onCheckedChange = prefs::setGaplessQueue,
    )
}
