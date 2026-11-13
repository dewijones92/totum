package com.dewijones92.totum.ui.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dewijones92.totum.R
import com.dewijones92.totum.phoneuse.UsageAccessCard
import com.dewijones92.totum.stats.TopEntry

internal fun LazyListScope.phoneUse(page: StatsViewModel.PhonePage, byTime: Boolean, actions: PhoneUseActions) {
    item { SectionTitle(R.string.phone_use_stats_title) }
    if (!page.accessGranted) item { UsageAccessCard(granted = false) }
    val hasData = page.today.unlocks > 0 || page.trend.unlocks > 0
    if (!hasData) {
        if (page.accessGranted) item { Hint(R.string.phone_use_stats_none) }
        return
    }
    item { TodayCard(page) }
    item { Hint(R.string.phone_use_stats_per_hour) }
    item { BarChart(page.today.perHour, byHours = false, onBar = actions.onBar) }
    item { Hint(R.string.phone_use_stats_per_hour_hint) }
    item { SectionTitle(R.string.phone_use_stats_trend) }
    item { TrendChips(page.trend.days, byTime, actions.onTrendDays, actions.onByTime) }
    item { BarChart(page.trend.bars, byHours = byTime, onBar = actions.onBar) }
    item { Hint(R.string.phone_use_stats_trend_hint) }
    item { TrendTotals(page) }
    firstAppRows(page, actions.onApp, actions.appLabel)
    item { NudgeCard(page) }
}

@Composable
private fun TodayCard(page: StatsViewModel.PhonePage) = StatCard(R.string.phone_use_stats_today) {
    val today = page.today
    CountLine(R.string.phone_use_stats_unlocks, today.unlocks)
    StatLine(stringResource(R.string.phone_use_stats_screen), heard(today.screenMs))
    StatLine(stringResource(R.string.phone_use_stats_longest_gap), today.longestGapMs?.let(::heard) ?: DASH)
    StatLine(stringResource(R.string.phone_use_stats_since_last), today.sinceLastUnlockMs?.let(::heard) ?: DASH)
}

@Composable
private fun TrendChips(days: Int, byTime: Boolean, onDays: (Int) -> Unit, onByTime: (Boolean) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            days == WEEK_DAYS,
            { onDays(WEEK_DAYS) },
            { Text(stringResource(R.string.phone_use_stats_7_days)) },
            Modifier.testTag("phone-use-7-days"),
        )
        FilterChip(
            days == MONTH_DAYS,
            { onDays(MONTH_DAYS) },
            { Text(stringResource(R.string.phone_use_stats_30_days)) },
            Modifier.testTag("phone-use-30-days"),
        )
    }
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!byTime, { onByTime(false) }, { Text(stringResource(R.string.phone_use_stats_unlocks)) })
        FilterChip(byTime, { onByTime(true) }, { Text(stringResource(R.string.phone_use_stats_screen)) })
    }
}

@Composable
private fun TrendTotals(page: StatsViewModel.PhonePage) = StatCard(R.string.phone_use_stats_summary) {
    val trend = page.trend
    StatLine(
        stringResource(R.string.phone_use_stats_total, trend.days),
        pluralStringResource(R.plurals.phone_use_stats_unlocks_count, trend.unlocks, trend.unlocks),
    )
    StatLine(
        stringResource(R.string.phone_use_stats_average),
        stringResource(
            R.string.phone_use_stats_average_value,
            "%.1f".format(trend.averageUnlocksPerDay),
            heard(trend.averageScreenMsPerDay),
        ),
    )
}

private fun LazyListScope.firstAppRows(
    page: StatsViewModel.PhonePage,
    onApp: (TopEntry) -> Unit,
    appLabel: (String) -> String,
) {
    if (page.firstApps.isEmpty()) return
    item { SectionTitle(R.string.phone_use_stats_first_apps) }
    items(page.firstApps.withIndex().toList(), key = { "phone-app-${it.value.key}" }) { (index, entry) ->
        StatLine(
            "${index + 1}. ${appLabel(entry.key)}",
            "${pluralStringResource(R.plurals.phone_use_stats_unlocks_count, entry.plays, entry.plays)} · " +
                heard(entry.heardMs),
            Modifier.clickable { onApp(entry) },
        )
    }
}

@Composable
private fun NudgeCard(page: StatsViewModel.PhonePage) {
    val nudges = page.nudges
    if (nudges.shown == 0) return
    StatCard(R.string.phone_use_stats_nudges) {
        CountLine(R.string.phone_use_stats_nudge_shown, nudges.shown)
        CountLine(R.string.phone_use_stats_nudge_continued, nudges.continued)
        CountLine(R.string.phone_use_stats_nudge_left, nudges.left)
        CountLine(R.string.phone_use_stats_nudge_unanswered, nudges.unanswered)
    }
}

private const val DASH = "–"
private const val WEEK_DAYS = 7
private const val MONTH_DAYS = 30
