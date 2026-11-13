package com.dewijones92.totum.phoneuse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.reminders.kit.KeepScreenAwake
import com.dewijones92.totum.stats.UnlockNudgeSettings
import com.dewijones92.totum.ui.common.BackHeader
import com.dewijones92.totum.ui.settings.QuestCard
import com.dewijones92.totum.ui.settings.SnappingSlider

@Composable
fun PhoneUseScreen(phoneUse: PhoneUse, onBack: () -> Unit, modifier: Modifier = Modifier) {
    KeepScreenAwake("phone use page")
    val settings by phoneUse.settings.collectAsStateWithLifecycle()
    var generation by remember { mutableIntStateOf(0) }
    val access = remember(generation) { phoneUse.accessGranted() }
    LifecycleResumeEffect(Unit) {
        generation++
        onPauseOrDispose { }
    }
    LaunchedEffect(generation) { phoneUse.sync("phone use page resumed") }
    val update: (String, (UnlockNudgeSettings) -> UnlockNudgeSettings) -> Unit = { source, change ->
        phoneUse.update("page: $source", change)
    }
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            BackHeader(stringResource(R.string.phone_use_name), onBack)
            Text(
                stringResource(R.string.phone_use_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            UsageAccessCard(access)
            NudgeCard(settings, access, update)
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun NudgeCard(
    settings: UnlockNudgeSettings,
    access: Boolean,
    update: (String, (UnlockNudgeSettings) -> UnlockNudgeSettings) -> Unit,
) {
    val resources = LocalResources.current
    QuestCard(Icons.Outlined.HourglassTop, stringResource(R.string.phone_use_nudge_card)) {
        Text(
            stringResource(if (settings.enabled) R.string.phone_use_nudge_on else R.string.phone_use_nudge_off),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("phone-use-nudge-status"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.phone_use_nudge_switch), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.phone_use_nudge_switch_summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = settings.enabled,
                onCheckedChange = { on -> update("switch") { it.copy(enabled = on) } },
                modifier = Modifier.testTag("phone-use-nudge-switch"),
            )
        }
        SnappingSlider(
            label = stringResource(R.string.phone_use_nudge_within),
            value = settings.withinMinutes,
            range = UnlockNudgeSettings.MIN_WITHIN_MINUTES..UnlockNudgeSettings.MAX_WITHIN_MINUTES,
            step = 1,
            shown = { minutes -> resources.getQuantityString(R.plurals.phone_use_nudge_minutes, minutes, minutes) },
            tag = "phone-use-nudge-within",
            onCommit = { minutes -> update("window") { it.copy(withinMinutes = minutes) } },
        )
        if (settings.enabled && !access) {
            Text(
                stringResource(R.string.phone_use_nudge_needs_access),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            stringResource(R.string.phone_use_nudge_honest),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
