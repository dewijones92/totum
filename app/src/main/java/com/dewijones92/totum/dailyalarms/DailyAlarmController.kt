package com.dewijones92.totum.dailyalarms

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

interface DailyAlarmPorts {
    fun schedule(alarmId: String, at: Instant?, ring: Boolean)
    fun showQuestion(alarm: DailyAlarm, question: AlarmEffect.ShowQuestion)
    fun hideQuestion(alarmId: String)
    fun showBoard(board: AlarmBoard)
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
}

class InMemoryDailyAlarmStore(
    var alarms: List<DailyAlarm> = listOf(DailyAlarm(id = DailyAlarmController.PICKUP_ID)),
    var states: Map<String, DayState> = emptyMap(),
) : DailyAlarmStore {
    override fun loadAlarms() = alarms
    override fun saveAlarms(alarms: List<DailyAlarm>) {
        this.alarms = alarms
    }
    override fun loadStates() = states
    override fun saveStates(states: Map<String, DayState>) {
        this.states = states
    }
}

data class DailyAlarmsView(
    val alarms: List<DailyAlarm>,
    val states: Map<String, DayState>,
    val nextWakes: Map<String, Instant?>,
) {
    fun state(id: String): DayState = states[id] ?: DayState.Idle
    val ringing: Boolean get() = states.values.any { it is DayState.Ringing }
}

class DailyAlarmController(
    private val store: DailyAlarmStore,
    private val ports: DailyAlarmPorts,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private var alarms = store.loadAlarms().ifEmpty { listOf(DailyAlarm(id = PICKUP_ID)) }
    private var states = store.loadStates()
    private val wakes = mutableMapOf<String, Instant?>()
    private var lastEvent = "none"
    private val _view = MutableStateFlow(DailyAlarmsView(alarms, states, wakes.toMap()))
    val view: StateFlow<DailyAlarmsView> = _view.asStateFlow()

    val ringingNow: Boolean get() = _view.value.ringing

    val diagnostics: Map<String, String>
        @Synchronized get() = buildMap {
            put("dailyAlarms.count", alarms.size.toString())
            put("dailyAlarms.lastEvent", lastEvent)
            alarms.forEach { alarm ->
                val key = "dailyAlarms.${alarm.id}"
                put("$key.state", (states[alarm.id] ?: DayState.Idle).toString())
                put("$key.nextWake", wakes[alarm.id]?.toString() ?: "-")
                put("$key.settings", alarm.toString())
            }
            val board = board()
            put("dailyAlarms.board.pinned", board.pinned.toString())
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
        result.effects.forEach { effect -> execute(alarm, effect) }
        wakes[alarm.id] = result.nextWake
        ports.schedule(alarm.id, result.nextWake, ring = result.state is DayState.Set)
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
        _view.value = DailyAlarmsView(alarms, states, wakes.toMap())
        ports.showBoard(board())
    }

    fun board(): AlarmBoard = alarmBoard(alarms, states, clock(), zone())

    companion object {
        const val TAG = "DailyAlarm"
        const val PICKUP_ID = "pickup"
    }
}
