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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dewijones92.totum.R
import com.dewijones92.totum.domain.formatBytes
import com.dewijones92.totum.jobs.LastRun
import com.dewijones92.totum.jobs.LastRuns
import com.dewijones92.totum.jobs.QueueTidyWorker
import com.dewijones92.totum.jobs.agoText
import com.dewijones92.totum.mobiledata.MobileDataMeter
import com.dewijones92.totum.mobiledata.reachedLimit
import com.dewijones92.totum.settings.AppPreferences
import com.dewijones92.totum.ui.common.LocalNow

internal val TIDY_AGES = listOf(7, 14, 30, 60)
internal val DATA_LIMITS_MB = listOf(0, 500, 1_000, 2_000, 5_000)
internal const val TIDY_AGE_TAG = "tidyAge"
internal const val DATA_LIMIT_TAG = "dataLimit"
private const val SUGGESTED_LIMIT_MB = 1_000
private const val MB_PER_GB = 1_000

@Composable
internal fun TidyQueueSettings(settings: AppPreferences.Settings, prefs: AppPreferences, lastRuns: LastRuns) {
    val last by lastRuns.observe(QueueTidyWorker.JOB_NAME).collectAsStateWithLifecycle(null)
    val context = LocalContext.current
    val now = LocalNow.current.toEpochMilli()
    val lastLine = tidyLastLine(
        last,
        { then, at -> context.agoText(then, at) },
        now,
        stringResource(R.string.settings_tidy_never)
    )
    AnchoredRow(SettingsRow.TIDY_QUEUE) {
        SwitchRow(
            label = stringResource(R.string.settings_tidy_queue),
            summary = stringResource(R.string.settings_tidy_queue_summary) + "\n" + lastLine,
            checked = settings.tidyQueue,
            onCheckedChange = prefs::setTidyQueue,
        )
    }
    AnchoredRow(SettingsRow.TIDY_AGE) {
        ChipChoiceRow(
            label = stringResource(R.string.settings_tidy_age),
            choices = TIDY_AGES,
            selected = settings.tidyAfterDays,
            enabled = settings.tidyQueue,
            tag = TIDY_AGE_TAG,
            onSelect = prefs::setTidyAfterDays,
        ) { days -> pluralStringResource(R.plurals.settings_tidy_age_days, days, days) }
    }
}

@Composable
private fun tidyLastLine(last: LastRun?, ago: (Long, Long) -> String, now: Long, never: String): String {
    last ?: return never
    return if (last.succeeded) {
        stringResource(R.string.settings_tidy_last_ok, ago(last.ranAtMs, now), last.detail)
    } else {
        stringResource(R.string.settings_tidy_last_failed, last.detail)
    }
}

@Composable
internal fun DataLimitRow(meter: MobileDataMeter, settings: AppPreferences.Settings, prefs: AppPreferences) {
    val used by meter.state.collectAsStateWithLifecycle()
    val limitMb = settings.dataLimitMb
    val usedText = formatBytes(used.bytes)
    val today = when {
        limitMb <= 0 -> stringResource(R.string.settings_data_limit_today, usedText)
        used.reachedLimit(limitMb) ->
            stringResource(R.string.settings_data_limit_reached, usedText, limitText(limitMb))
        else -> stringResource(R.string.settings_data_limit_today_of, usedText, limitText(limitMb))
    }
    ChipChoiceRow(
        label = stringResource(R.string.settings_data_limit),
        summary = stringResource(R.string.settings_data_limit_summary) + "\n" + today,
        choices = DATA_LIMITS_MB,
        selected = limitMb,
        tag = DATA_LIMIT_TAG,
        onSelect = prefs::setDataLimitMb,
    ) { choice ->
        when {
            choice == 0 -> stringResource(R.string.settings_data_limit_off)
            choice == SUGGESTED_LIMIT_MB -> stringResource(R.string.settings_data_limit_suggested, limitText(choice))
            else -> limitText(choice)
        }
    }
}

@Composable
private fun limitText(megabytes: Int): String = if (megabytes >= MB_PER_GB) {
    stringResource(R.string.settings_data_limit_gb, megabytes / MB_PER_GB)
} else {
    stringResource(R.string.settings_data_limit_mb, megabytes)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipChoiceRow(
    label: String,
    choices: List<Int>,
    selected: Int,
    tag: String,
    onSelect: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    choiceLabel: @Composable (Int) -> String,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        summary?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                FilterChip(
                    selected = choice == selected,
                    onClick = { onSelect(choice) },
                    enabled = enabled,
                    label = { Text(choiceLabel(choice)) },
                    modifier = Modifier.testTag("$tag:$choice"),
                )
            }
        }
    }
}
