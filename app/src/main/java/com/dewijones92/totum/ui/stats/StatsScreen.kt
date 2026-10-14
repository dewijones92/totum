package com.dewijones92.totum.ui.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dewijones92.totum.R
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.Period
import com.dewijones92.totum.stats.TopKind
import com.dewijones92.totum.stats.Totals
import com.dewijones92.totum.ui.common.BackHeader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun StatsScreen(container: AppContainer, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: StatsViewModel = viewModel(factory = StatsViewModel.factory(container))
    val page by viewModel.page.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.reload() }
    BackHandler(enabled = detail != null) { viewModel.closeDetail() }
    detail?.let { open ->
        StatsDetail(open, onBack = viewModel::closeDetail, modifier = modifier)
        return
    }
    var byHours by rememberSaveable { mutableStateOf(false) }
    var topKind by rememberSaveable { mutableStateOf(TopKind.ARTISTS) }
    LazyColumn(modifier.fillMaxSize().testTag(STATS_SCREEN_TAG)) {
        item { BackHeader(stringResource(R.string.stats_title), onBack) }
        val current = page
        if (current == null) {
            item { CircularProgressIndicator(Modifier.padding(32.dp)) }
            return@LazyColumn
        }
        item { PeriodChips(current.period, viewModel::show) }
        item { Overview(current) }
        item { SectionTitle(R.string.stats_over_time) }
        item { MeasureChips(byHours) { byHours = it } }
        item { BarChart(current.bars, byHours, viewModel::openBar) }
        item { Hint(R.string.stats_over_time_hint) }
        topSection(current, topKind, { topKind = it }, viewModel::openTop)
        item { SectionTitle(R.string.stats_when) }
        item { Heatmap(current.heatmap) }
        sideQuests(current)
        item { Hint(R.string.stats_counting_note) }
    }
}

@Composable
private fun PeriodChips(selected: Period, onSelect: (Period) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Period.entries.forEach { period ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(stringResource(period.label)) },
            )
        }
    }
}

@Composable
private fun MeasureChips(byHours: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!byHours, { onChange(false) }, { Text(stringResource(R.string.stats_measure_plays)) })
        FilterChip(byHours, { onChange(true) }, { Text(stringResource(R.string.stats_measure_hours)) })
    }
}

@Composable
private fun Overview(page: StatsViewModel.Page) {
    StatCard(R.string.stats_listening) {
        TotalsLine(R.string.stats_all_kinds, page.totals.all)
        ListenKind.entries.forEach { kind -> TotalsLine(kind.label, page.totals.byKind[kind] ?: Totals(0, 0)) }
        StatLine(stringResource(R.string.stats_watched), heard(page.totals.watchedMs))
        StatLine(stringResource(R.string.stats_listened), heard(page.totals.listenedMs))
        Text(
            stringResource(R.string.stats_streak, page.streaks.current, page.streaks.longest),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun TotalsLine(label: Int, totals: Totals) = StatLine(
    stringResource(label),
    "${pluralStringResource(R.plurals.stats_plays, totals.plays, totals.plays)} · ${heard(totals.heardMs)}",
)

private fun LazyListScope.topSection(
    page: StatsViewModel.Page,
    kind: TopKind,
    onKind: (TopKind) -> Unit,
    onOpen: (TopKind, com.dewijones92.totum.stats.TopEntry) -> Unit,
) {
    item { SectionTitle(R.string.stats_top) }
    item {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopKind.entries.forEach { each ->
                FilterChip(each == kind, { onKind(each) }, { Text(stringResource(each.label)) })
            }
        }
    }
    val entries = page.top[kind].orEmpty()
    if (entries.isEmpty()) item { Hint(R.string.stats_nothing) }
    items(entries.withIndex().toList(), key = { "top-${kind.name}-${it.value.key}" }) { (index, entry) ->
        StatLine(
            "${index + 1}. ${entry.label}",
            "${pluralStringResource(R.plurals.stats_plays, entry.plays, entry.plays)} · ${heard(entry.heardMs)}",
            Modifier.clickable { onOpen(kind, entry) },
        )
    }
}

private fun LazyListScope.sideQuests(page: StatsViewModel.Page) {
    item {
        val e = page.exsurge
        StatCard(R.string.stats_exsurge) {
            CountLine(R.string.stats_summons, e.summons)
            CountLine(R.string.stats_obeyed, e.obeyed)
            CountLine(R.string.stats_skipped, e.skipped)
            CountLine(R.string.stats_missed, e.missed)
            CountLine(R.string.stats_snoozes, e.snoozes)
            CountLine(R.string.stats_walk_presses, e.walkPresses)
            CountLine(R.string.stats_pauses, e.pauses)
            CountLine(R.string.stats_steps, e.steps)
            StatLine(stringResource(R.string.stats_standing), heard(e.standingMs))
            CountLine(R.string.stats_practised, e.practised)
            StatLine(stringResource(R.string.stats_off), heard(e.offMs))
        }
    }
    item {
        StatCard(R.string.stats_nudges) {
            CountLine(R.string.stats_nudged, page.nudges.posted)
            CountLine(R.string.stats_nudge_on, page.nudges.turnedOn)
            CountLine(R.string.stats_nudge_not_today, page.nudges.notToday)
            CountLine(R.string.stats_nudge_ignored, page.nudges.ignored)
        }
    }
    item { AlarmCard(page) }
    item { UseCard(page) }
}

@Composable
private fun AlarmCard(page: StatsViewModel.Page) = StatCard(R.string.stats_alarms) {
    val a = page.alarms
    CountLine(R.string.stats_alarm_asked, a.asked)
    CountLine(R.string.stats_alarm_yes, a.yes)
    CountLine(R.string.stats_alarm_no, a.no)
    CountLine(R.string.stats_alarm_rang, a.rang)
    CountLine(R.string.stats_alarm_snoozed, a.snoozed)
    CountLine(R.string.stats_alarm_dismissed, a.dismissed)
    StatLine(stringResource(R.string.stats_alarm_stop_time), a.averageStopMs?.let(::heard) ?: "–")
}

@Composable
private fun UseCard(page: StatsViewModel.Page) = StatCard(R.string.stats_use) {
    val u = page.use
    CountLine(R.string.stats_searches, u.searches)
    CountLine(R.string.stats_downloads, u.downloads)
    StatLine(stringResource(R.string.stats_downloaded), megabytes(u.downloadedBytes))
    CountLine(R.string.stats_pins, u.pins)
    CountLine(R.string.stats_shuffles, u.shuffles)
    CountLine(R.string.stats_videos_shown, u.videosShown)
}

@Composable
private fun StatsDetail(detail: StatsViewModel.Detail, onBack: () -> Unit, modifier: Modifier) {
    val zone = ZoneId.systemDefault()
    val format = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
    LazyColumn(modifier.fillMaxSize()) {
        item { BackHeader(detail.title, onBack) }
        if (detail.listens.isEmpty()) item { Hint(R.string.stats_detail_empty) }
        items(detail.listens, key = { it.playId }) { listen ->
            StatLine(
                listen.title,
                stringResource(
                    R.string.stats_detail_line,
                    Instant.ofEpochMilli(listen.at).atZone(zone).format(format),
                    heard(listen.heardMs),
                ),
            )
        }
    }
}

internal const val STATS_SCREEN_TAG = "stats-screen"
