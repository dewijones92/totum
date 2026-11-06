package com.dewijones92.totum.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.data.sponsorblock.SkipCategory

/** The SponsorBlock categories, each independently on or off. */
@Composable
internal fun SkipCategorySection(enabled: Set<SkipCategory>, onChange: (Set<SkipCategory>) -> Unit) {
    AnchoredRow(SettingsRow.SKIP_CATEGORIES) {
        Column {
            Text(
                text = stringResource(R.string.settings_skip_section),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(R.string.settings_skip_supporting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    SkipCategory.entries.forEach { category ->
        SkipCategoryRow(
            category = category,
            enabled = category in enabled,
            onToggle = { on -> onChange(if (on) enabled + category else enabled - category) },
        )
    }
}

/**
 * One SponsorBlock category. Offered individually because they are not equivalent: a
 * sponsor read is unambiguously not the content, whereas an intro or a recap is content
 * to some people — so the opinionated ones are opt-in rather than assumed.
 */
@Composable
private fun SkipCategoryRow(category: SkipCategory, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!enabled) }
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = stringResource(category.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

private fun SkipCategory.labelRes(): Int = when (this) {
    SkipCategory.SPONSOR -> R.string.skip_sponsor
    SkipCategory.SELF_PROMO -> R.string.skip_self_promo
    SkipCategory.INTERACTION -> R.string.skip_interaction
    SkipCategory.INTRO -> R.string.skip_intro
    SkipCategory.OUTRO -> R.string.skip_outro
    SkipCategory.PREVIEW -> R.string.skip_preview
    SkipCategory.MUSIC_OFFTOPIC -> R.string.skip_music_offtopic
    SkipCategory.FILLER -> R.string.skip_filler
}
