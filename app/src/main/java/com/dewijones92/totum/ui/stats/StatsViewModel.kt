package com.dewijones92.totum.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.exsurge.BreakOutcome
import com.dewijones92.totum.exsurge.OutcomeKind
import com.dewijones92.totum.stats.AlarmTotals
import com.dewijones92.totum.stats.Bar
import com.dewijones92.totum.stats.BreakKind
import com.dewijones92.totum.stats.BreakRecord
import com.dewijones92.totum.stats.ExsurgeTotals
import com.dewijones92.totum.stats.ListenTotals
import com.dewijones92.totum.stats.NudgeTotals
import com.dewijones92.totum.stats.Period
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.Streaks
import com.dewijones92.totum.stats.TopEntry
import com.dewijones92.totum.stats.TopKind
import com.dewijones92.totum.stats.UseTotals
import com.dewijones92.totum.stats.alarmTotals
import com.dewijones92.totum.stats.bars
import com.dewijones92.totum.stats.exsurgeTotals
import com.dewijones92.totum.stats.forTop
import com.dewijones92.totum.stats.heatmap
import com.dewijones92.totum.stats.inBar
import com.dewijones92.totum.stats.nudgeTotals
import com.dewijones92.totum.stats.since
import com.dewijones92.totum.stats.streaks
import com.dewijones92.totum.stats.top
import com.dewijones92.totum.stats.totals
import com.dewijones92.totum.stats.useTotals
import com.dewijones92.totum.stats.within
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class StatsViewModel(
    private val load: suspend () -> List<StatEvent>,
    private val breaks: () -> List<BreakRecord>,
    private val exsurgeOn: () -> Boolean,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    data class Page(
        val period: Period,
        val totals: ListenTotals,
        val bars: List<Bar>,
        val streaks: Streaks,
        val top: Map<TopKind, List<TopEntry>>,
        val heatmap: List<List<Long>>,
        val exsurge: ExsurgeTotals,
        val nudges: NudgeTotals,
        val alarms: AlarmTotals,
        val use: UseTotals,
    )

    data class Detail(val title: String, val listens: List<StatEvent.Listen>)

    private var events: List<StatEvent> = emptyList()
    private val _page = MutableStateFlow<Page?>(null)
    val page: StateFlow<Page?> = _page.asStateFlow()
    private val _detail = MutableStateFlow<Detail?>(null)
    val detail: StateFlow<Detail?> = _detail.asStateFlow()

    init {
        viewModelScope.launch {
            events = load()
            show(Period.WEEK)
        }
    }

    fun show(period: Period) {
        val at = now()
        val listens = events.filterIsInstance<StatEvent.Listen>()
        val inPeriod = listens.within(period, at, zone())
        val since = period.since(at, zone())?.toEpochMilli()
        _page.value = Page(
            period = period,
            totals = totals(inPeriod),
            bars = bars(inPeriod, period, at, zone()),
            streaks = streaks(listens, at.atZone(zone()).toLocalDate(), zone()),
            top = TopKind.entries.associateWith { top(inPeriod, it) },
            heatmap = heatmap(inPeriod, zone()),
            exsurge = exsurgeTotals(breaks(), events, since, at.toEpochMilli(), exsurgeOn()),
            nudges = nudgeTotals(events, since),
            alarms = alarmTotals(events, since),
            use = useTotals(events, since),
        )
        Diag.log("stats", "showing $period: ${inPeriod.size} listen(s) of ${listens.size}, ${events.size} event(s)")
    }

    fun openBar(bar: Bar) = open(bar.label, current().inBar(bar, zone()))

    fun openTop(kind: TopKind, entry: TopEntry) = open(entry.label, current().forTop(kind, entry.key))

    fun closeDetail() {
        _detail.value = null
    }

    private fun current(): List<StatEvent.Listen> {
        val period = _page.value?.period ?: Period.WEEK
        return events.filterIsInstance<StatEvent.Listen>().within(period, now(), zone())
    }

    private fun open(title: String, listens: List<StatEvent.Listen>) {
        Diag.log("stats", "drill into \"$title\": ${listens.size} listen(s)")
        _detail.value = Detail(title, listens.sortedByDescending { it.at })
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                StatsViewModel(
                    load = container.statsLog::all,
                    breaks = { container.exsurge.breaks.map(BreakOutcome::record) },
                    exsurgeOn = { container.exsurge.view.value.settings.enabled },
                )
            }
        }
    }
}

private fun BreakOutcome.record() = BreakRecord(
    at = resolvedAt.toEpochMilli(),
    kind = when (kind) {
        OutcomeKind.COMPLETED -> BreakKind.COMPLETED
        OutcomeKind.SKIPPED -> BreakKind.SKIPPED
        OutcomeKind.MISSED -> BreakKind.MISSED
    },
    credited = credited,
    snoozes = snoozes,
    steps = steps,
    standingMs = breakStartedAt?.let { Duration.between(it, resolvedAt).toMillis() } ?: 0,
    practised = practised,
)
