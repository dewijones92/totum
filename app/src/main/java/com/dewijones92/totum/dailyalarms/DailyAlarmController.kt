package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.stats.AlarmPhase
import com.dewijones92.totum.stats.AlarmStep
import com.dewijones92.totum.stats.StatEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

interface DailyAlarmPorts {
    fun schedule(alarmId: String, at: Instant?, ring: Boolean)
    fun showQuestion(alarm: DailyAlarm, question: AlarmEffect.ShowQuestion)
    fun hideQuestion(alarmId: String)
    fun showBoard(board: AlarmBoard)
    fun refreshBoardAt(at: Instant) = Unit
    fun ring(alarm: DailyAlarm, time: LocalTime)
    fun stopRinging(alarmId: String)
}

object NoDailyAlarmPorts : DailyAlarmPorts {
    override fun schedule(alarmId: String, at: Instant?, ring: Boolean) = Unit
    override fun showQuestion(alarm: DailyAlarm, question: AlarmEffect.ShowQuestion) = Unit
    override fun hideQuestion(alarmId: String) = Unit
    override fun showBoard(board: AlarmBoard) = Unit
    override fun ring(alarm: DailyAlarm, time: LocalTime) = Unit
    override fun stopRinging(alarmId: String) = Unit
}

interface DailyAlarmStore {
    fun loadAlarms(): List<DailyAlarm>
    fun saveAlarms(alarms: List<DailyAlarm>)
    fun loadStates(): Map<String, DayState>
    fun saveStates(states: Map<String, DayState>)
    fun loadRecentTitles(): List<String>
    fun saveRecentTitles(titles: List<String>)
}

class InMemoryDailyAlarmStore(
    var alarms: List<DailyAlarm> = listOf(DailyAlarm(id = DailyAlarmController.PICKUP_ID)),
    var states: Map<String, DayState> = emptyMap(),
    var recentTitles: List<String> = emptyList(),
) : DailyAlarmStore {
    override fun loadAlarms() = alarms
    override fun saveAlarms(alarms: List<DailyAlarm>) {
        this.alarms = alarms
    }
    override fun loadStates() = states
    override fun saveStates(states: Map<String, DayState>) {
        this.states = states
    }
    override fun loadRecentTitles() = recentTitles
    override fun saveRecentTitles(titles: List<String>) {
        recentTitles = titles
    }
}

data class DailyAlarmsView(
    val alarms: List<DailyAlarm>,
    val states: Map<String, DayState>,
    val nextWakes: Map<String, Instant?>,
    val recentTitles: List<String> = emptyList(),
) {
    val daily: List<DailyAlarm> get() = alarms.filterNot { it.isOneOff }
    val oneOffs: List<DailyAlarm> get() = alarms.filter { it.isOneOff }.sortedBy { it.once?.atTime(it.defaultTime) }

    fun state(id: String): DayState = states[id] ?: DayState.Idle
    val ringing: Boolean get() = states.values.any { it is DayState.Ringing }
}

@Suppress("TooManyFunctions")
class DailyAlarmController(
    private val store: DailyAlarmStore,
    private val ports: DailyAlarmPorts,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val record: (StatEvent) -> Unit = {},
) {
    private var alarms = store.loadAlarms().ifEmpty { listOf(DailyAlarm(id = PICKUP_ID)) }
    private var states = store.loadStates()
    private var recentTitles = store.loadRecentTitles()
    private val wakes = mutableMapOf<String, Instant?>()
    private var lastEvent = "none"
    private val _view = MutableStateFlow(DailyAlarmsView(alarms, states, wakes.toMap(), recentTitles))
    val view: StateFlow<DailyAlarmsView> = _view.asStateFlow()

    val ringingNow: Boolean get() = _view.value.ringing

    val diagnostics: Map<String, String>
        @Synchronized get() = buildMap {
            put("dailyAlarms.count", alarms.size.toString())
            put("dailyAlarms.oneOffs", alarms.count { it.isOneOff }.toString())
            put("dailyAlarms.recentTitles", recentTitles.size.toString())
            put("dailyAlarms.lastEvent", lastEvent)
            alarms.forEach { alarm ->
                val key = "dailyAlarms.${alarm.id}"
                put("$key.state", (states[alarm.id] ?: DayState.Idle).toString())
                put("$key.nextWake", wakes[alarm.id]?.toString() ?: "-")
                put("$key.settings", alarm.toString())
            }
            val board = board()
            put("dailyAlarms.board.heading", board.heading.toString())
            put("dailyAlarms.board.refreshAt", board.refreshAt.toString())
            put(
                "dailyAlarms.board.rows",
                board.rows.joinToString(" | ") { "${it.time} ${it.label} ${it.date} ${it.status}" }
            )
        }

    @Synchronized
    fun dispatch(alarmId: String, event: AlarmEvent, source: String) {
        val alarm = alarms.firstOrNull { it.id == alarmId }
        if (alarm == null) {
            Diag.warn(TAG, "dewidebug dailyalarm $event from $source for unknown alarm $alarmId")
            return
        }
        lastEvent = "$alarmId $event from $source at ${clock()}"
        run(alarm, event, source)
        publish()
    }

    @Synchronized
    fun tickAll(source: String) {
        lastEvent = "tick all from $source at ${clock()}"
        alarms.forEach { run(it, AlarmEvent.Tick, source) }
        publish()
    }

    @Synchronized
    fun update(source: String, change: (List<DailyAlarm>) -> List<DailyAlarm>) {
        val before = alarms
        alarms = change(alarms)
        store.saveAlarms(alarms)
        Diag.log(TAG, "dewidebug dailyalarm settings from=$source ${before.size} -> ${alarms.size} alarm(s): $alarms")
        (before.map { it.id } - alarms.map { it.id }.toSet()).forEach { removed ->
            ports.hideQuestion(removed)
            ports.stopRinging(removed)
            ports.schedule(removed, null, ring = false)
            states = states - removed
            wakes.remove(removed)
        }
        alarms.forEach { run(it, AlarmEvent.Tick, "settings") }
        publish()
    }

    private fun run(alarm: DailyAlarm, event: AlarmEvent, source: String) {
        val before = states[alarm.id] ?: DayState.Idle
        val result = DailyAlarmMachine.apply(alarm, before, event, clock(), zone())
        states = states + (alarm.id to result.state)
        store.saveStates(states)
        Diag.log(
            TAG,
            "dewidebug dailyalarm ${alarm.id} $event from $source: $before -> ${result.state}; " +
                "notes=${result.notes}; effects=${result.effects}; nextWake=${result.nextWake}",
        )
        if (event !is AlarmEvent.Tick || before::class != result.state::class) {
            record(
                StatEvent.AlarmChange(
                    clock().toEpochMilli(),
                    alarm.id,
                    alarm.label,
                    event.step,
                    before.phase,
                    result.state.phase,
                ),
            )
        }
        result.effects.forEach { effect -> execute(alarm, effect) }
        wakes[alarm.id] = result.nextWake
        ports.schedule(alarm.id, result.nextWake, ring = result.state is DayState.Set)
        if (alarm.isOneOff && result.state.finished) forget(alarm, result.state, source)
    }

    private val DayState.finished: Boolean get() = this is DayState.Done || this == DayState.Idle

    private fun forget(alarm: DailyAlarm, state: DayState, source: String) {
        alarms = alarms.filterNot { it.id == alarm.id }
        states = states - alarm.id
        wakes.remove(alarm.id)
        store.saveAlarms(alarms)
        store.saveStates(states)
        ports.hideQuestion(alarm.id)
        ports.schedule(alarm.id, null, ring = false)
        Diag.log(
            TAG,
            "dewidebug dailyalarm ${alarm.id} one-off \"${alarm.label}\" removed from $source: it ended $state"
        )
    }

    @Synchronized
    fun addOneOff(title: String, date: LocalDate, time: LocalTime, source: String): Boolean {
        val label = title.trim().ifEmpty { ONE_OFF_TITLE }
        val now = localNow()
        if (!date.atTime(time).isAfter(now)) {
            Diag.log(
                TAG,
                "dewidebug dailyalarm one-off \"$label\" $date $time from $source refused: it is already $now"
            )
            return false
        }
        val alarm = DailyAlarm.oneOff(DailyAlarm.ONE_OFF_PREFIX + UUID.randomUUID(), label, date, time)
        alarms = alarms + alarm
        store.saveAlarms(alarms)
        recentTitles = recentTitles.remembering(label)
        store.saveRecentTitles(recentTitles)
        lastEvent = "${alarm.id} one-off \"$label\" $date $time from $source at ${clock()}"
        Diag.log(TAG, "dewidebug dailyalarm ${alarm.id} one-off \"$label\" for $date $time from $source")
        run(alarm, AlarmEvent.SetOn(date, time), source)
        publish()
        return states[alarm.id] is DayState.Set
    }

    @Synchronized
    fun cancelOneOff(alarmId: String, source: String) {
        val alarm = alarms.firstOrNull { it.id == alarmId && it.isOneOff }
        if (alarm == null) {
            Diag.warn(TAG, "dewidebug dailyalarm cancel one-off from $source for unknown alarm $alarmId")
            return
        }
        lastEvent = "$alarmId one-off cancelled from $source at ${clock()}"
        val state = states[alarmId] ?: DayState.Idle
        if (state is DayState.Ringing || state is DayState.Snoozed) ports.stopRinging(alarmId)
        forget(alarm, DayState.Done(localNow().toLocalDate(), Outcome.CANCELLED), source)
        publish()
    }

    private fun execute(alarm: DailyAlarm, effect: AlarmEffect) {
        when (effect) {
            is AlarmEffect.ShowQuestion -> ports.showQuestion(alarm, effect)
            is AlarmEffect.HideQuestion -> ports.hideQuestion(effect.alarmId)
            is AlarmEffect.ShowSet, is AlarmEffect.HideSet -> Unit
            is AlarmEffect.Ring -> ports.ring(alarm, effect.time)
            is AlarmEffect.StopRinging -> ports.stopRinging(effect.alarmId)
        }
    }

    private fun publish() {
        _view.value = DailyAlarmsView(alarms, states, wakes.toMap(), recentTitles)
        val board = board()
        ports.showBoard(board)
        ports.refreshBoardAt(board.refreshAt)
    }

    fun board(): AlarmBoard = alarmBoard(alarms, states, clock(), zone())

    fun localNow(): LocalDateTime = LocalDateTime.ofInstant(clock(), zone())

    fun nextRing(alarm: DailyAlarm, state: DayState): NextRing? = alarm.nextRing(state, clock(), zone())

    @Synchronized
    fun setToday(alarmId: String, time: LocalTime, source: String): Boolean {
        val alarm = alarms.firstOrNull { it.id == alarmId }
        if (alarm == null) {
            Diag.warn(TAG, "dewidebug dailyalarm set today $time from $source for unknown alarm $alarmId")
            return false
        }
        val now = localNow()
        if (!time.isAfter(now.toLocalTime())) {
            Diag.log(TAG, "dewidebug dailyalarm $alarmId set today $time from $source refused: it is already $now")
            return false
        }
        val enabled = if (alarm.enabled) alarm else alarm.copy(enabled = true)
        if (!alarm.enabled) {
            alarms = alarms.map { if (it.id == alarmId) enabled else it }
            store.saveAlarms(alarms)
            Diag.log(TAG, "dewidebug dailyalarm $alarmId switched on by set today from $source")
        }
        lastEvent = "$alarmId set today $time from $source at ${clock()}"
        run(enabled, AlarmEvent.SetToday(time), source)
        publish()
        return states[alarmId] is DayState.Set
    }

    companion object {
        const val TAG = "DailyAlarm"
        const val PICKUP_ID = "pickup"
        const val ONE_OFF_TITLE = "Alarm"
    }
}

private val AlarmEvent.step: AlarmStep
    get() = when (this) {
        AlarmEvent.Tick -> AlarmStep.TICK
        is AlarmEvent.Answer -> AlarmStep.ANSWER
        is AlarmEvent.SetToday -> AlarmStep.SET_TODAY
        is AlarmEvent.SetOn -> AlarmStep.SET_ON
        AlarmEvent.Decline -> AlarmStep.DECLINE
        AlarmEvent.Change -> AlarmStep.CHANGE
        AlarmEvent.Cancel -> AlarmStep.CANCEL
        AlarmEvent.Snooze -> AlarmStep.SNOOZE
        AlarmEvent.Dismiss -> AlarmStep.DISMISS
    }

private val DayState.phase: AlarmPhase
    get() = when (this) {
        DayState.Idle -> AlarmPhase.IDLE
        is DayState.Asking -> AlarmPhase.ASKING
        is DayState.Set -> AlarmPhase.SET
        is DayState.Ringing -> AlarmPhase.RINGING
        is DayState.Snoozed -> AlarmPhase.SNOOZED
        is DayState.Done -> AlarmPhase.DONE
    }
