package com.dewijones92.totum.exsurge

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId

interface ExsurgePersistence {
    fun loadSettings(): ExsurgeSettings
    fun saveSettings(settings: ExsurgeSettings)
    fun loadMemory(): ExsurgeMemory
    fun saveMemory(memory: ExsurgeMemory)
    fun loadOutcomes(): List<BreakOutcome>
    fun saveOutcomes(outcomes: List<BreakOutcome>)
}

interface ExsurgePorts {
    fun scheduleWake(at: Instant?)
    fun showTakeover(request: TakeoverRequest)
    fun hideTakeover()
    fun speak(cue: Cue, summonsId: Long, volumePercent: Int)
    fun buzz(haptic: Haptic)
    fun openDestination(settings: ExsurgeSettings): Boolean
    fun pausePlayback(): Boolean
    fun resumePlayback(): String
    fun continueTotum(): String
    fun viewChanged(view: ExsurgeView)
}

object NoExsurgePorts : ExsurgePorts {
    override fun scheduleWake(at: Instant?) = Unit
    override fun showTakeover(request: TakeoverRequest) = Unit
    override fun hideTakeover() = Unit
    override fun speak(cue: Cue, summonsId: Long, volumePercent: Int) = Unit
    override fun buzz(haptic: Haptic) = Unit
    override fun openDestination(settings: ExsurgeSettings) = false
    override fun pausePlayback() = false
    override fun resumePlayback() = "no playback"
    override fun continueTotum() = "no playback"
    override fun viewChanged(view: ExsurgeView) = Unit
}

data class TakeoverRequest(
    val summonsId: Long,
    val call: Int,
    val snoozeMinutes: Int,
    val overOtherApps: Boolean,
)

data class ExsurgeView(
    val settings: ExsurgeSettings,
    val memory: ExsurgeMemory,
    val stats: ExsurgeStats,
    val nextWake: Instant?,
    val stepsAvailable: Boolean,
    val at: Instant,
    val zone: ZoneId,
) {
    val context: ExsurgeContext get() = ExsurgeContext(settings, zone, stepsAvailable)
    val mood: Mood get() = moodOf(memory.state, at)
    val banner: BannerLine get() = bannerLineOf(memory.state, at, context)
    val pauseAvailable: Boolean get() = settings.enabled && ExsurgeMachine.canPause(memory, at, zone)
    val actions: List<BannerAction> get() = bannerActionsOf(memory.state, settings.enabled, pauseAvailable)
}

class ExsurgeController(
    private val store: ExsurgePersistence,
    private val ports: ExsurgePorts,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val sensorStepsAvailable: () -> Boolean,
    private val maxOutcomes: Int = MAX_OUTCOMES,
) {
    private var settings = store.loadSettings()
    private var memory = store.loadMemory()
    private var outcomes = store.loadOutcomes()
    private var stepWindow = StepWindow(settings.walkWindow)
    private var simulatedSteps = false
    private var stepReadings = 0L
    private var lastStepTotal: Long? = null
    private var lastEvent = "none"

    private val _view = MutableStateFlow(viewAt(clock()))
    val view: StateFlow<ExsurgeView> = _view.asStateFlow()

    val diagnostics: Map<String, String>
        @Synchronized get() = buildMap {
            val view = viewAt(clock())
            put("exsurge.enabled", settings.enabled.toString())
            put("exsurge.state", memory.state.label())
            put("exsurge.nextWake", view.nextWake?.toString() ?: "-")
            put("exsurge.stepsAvailable", "${view.stepsAvailable} (simulated=$simulatedSteps, readings=$stepReadings)")
            put("exsurge.lastEvent", lastEvent)
            put("exsurge.today", view.stats.today.toString())
            put("exsurge.streakDays", view.stats.streakDays.toString())
            put("exsurge.rank", "${view.stats.rank.latin} laurels=${view.stats.laurels}")
            put("exsurge.settings", settings.toString())
        }

    @Synchronized
    fun dispatch(event: ExsurgeEvent, source: String) {
        lastEvent = "$event from $source at ${clock()}"
        apply(event, source)
        val seed = lastStepTotal
        val rising = when (event) {
            ExsurgeEvent.Go, ExsurgeEvent.JustWalk, ExsurgeEvent.ContinueTotum -> true
            else -> false
        }
        if (rising && memory.state is ExsurgeState.Rising && seed != null) {
            Diag.log(TAG, "dewidebug exsurge GO baseline = last step reading, total=$seed")
            apply(ExsurgeEvent.StepsCounted(seed), "baseline at GO")
        }
    }

    private fun apply(event: ExsurgeEvent, source: String) {
        val at = clock()
        val context = ExsurgeContext(settings, zone(), stepsAvailable)
        val transition = ExsurgeMachine.apply(memory, event, at, context)
        val changed = transition.memory != memory
        memory = transition.memory
        if (changed) store.saveMemory(memory)
        val noteText = transition.notes.joinToString(" | ").ifEmpty { "no change" }
        val effectText = transition.effects.joinToString(",") { it.label() }.ifEmpty { "none" }
        if (event !is ExsurgeEvent.StepsCounted || transition.effects.isNotEmpty()) {
            Diag.log(
                TAG,
                "dewidebug exsurge $event from=$source state=${memory.state.label()} " +
                    "notes=[$noteText] effects=[$effectText]"
            )
        }
        transition.effects.forEach { execute(it) }
        publish(at)
    }

    @Synchronized
    fun updateSettings(source: String, transform: (ExsurgeSettings) -> ExsurgeSettings) {
        val next = transform(settings).validated()
        if (next == settings) return
        Diag.log(TAG, "dewidebug exsurge settings from=$source ${diff(settings, next)}")
        if (next.walkWindow != settings.walkWindow) stepWindow = StepWindow(next.walkWindow)
        val turnedOff = settings.enabled && !next.enabled
        settings = next
        store.saveSettings(settings)
        dispatch(if (turnedOff) ExsurgeEvent.TurnOff else ExsurgeEvent.SettingsChanged, source)
    }

    @Synchronized
    fun onStepCounter(total: Long, source: String = "sensor", at: Instant = clock()) {
        stepReadings++
        lastStepTotal = total
        val inWindow = stepWindow.record(at, total)
        if (stepReadings % STEP_LOG_EVERY == 1L) {
            Diag.log(
                TAG,
                "dewidebug exsurge steps reading#$stepReadings total=$total inWindow=$inWindow " +
                    "state=${memory.state.label()} source=$source"
            )
        }
        when (memory.state) {
            is ExsurgeState.Sitting -> if (settings.walkResetSteps in 1..inWindow) {
                stepWindow.clear()
                dispatch(ExsurgeEvent.Walked, "$source inWindow=$inWindow")
            }
            is ExsurgeState.Rising, is ExsurgeState.OnBreak -> dispatch(ExsurgeEvent.StepsCounted(total), source)
            else -> Unit
        }
    }

    @Synchronized
    fun simulateSteps(total: Long) {
        simulatedSteps = true
        onStepCounter(total, "simulated")
        publish(clock())
    }

    @Synchronized
    fun refresh() = publish(clock())

    private val stepsAvailable: Boolean get() = simulatedSteps || sensorStepsAvailable()

    private fun execute(effect: ExsurgeEffect) {
        when (effect) {
            is ExsurgeEffect.Speak -> if (!settings.quietOffice) {
                ports.speak(
                    effect.cue,
                    summonsIdOf(memory.state, memory.nextSummonsId - 1),
                    settings.voiceVolumePercent,
                )
            }
            is ExsurgeEffect.Buzz -> ports.buzz(effect.haptic)
            is ExsurgeEffect.ShowTakeover -> ports.showTakeover(
                TakeoverRequest(
                    summonsId = effect.summonsId,
                    call = effect.call,
                    snoozeMinutes = settings.snoozeMinutes,
                    overOtherApps = settings.takeoverOverApps,
                ),
            )
            ExsurgeEffect.HideTakeover -> ports.hideTakeover()
            ExsurgeEffect.OpenDestination -> {
                val opened = ports.openDestination(settings)
                Diag.log(
                    TAG,
                    "dewidebug exsurge open ${settings.destinationPackage} " +
                        "route=${settings.destinationRoute} opened=$opened"
                )
            }
            ExsurgeEffect.PausePlayback -> Diag.log(
                TAG,
                "dewidebug exsurge pause playback paused=${ports.pausePlayback()}"
            )
            ExsurgeEffect.ResumePlayback -> Diag.log(
                TAG,
                "dewidebug exsurge resume playback: ${ports.resumePlayback()}"
            )
            ExsurgeEffect.ContinueTotum -> Diag.log(
                TAG,
                "dewidebug exsurge continue Totum: ${ports.continueTotum()}"
            )
            is ExsurgeEffect.Record -> record(effect.outcome)
        }
    }

    private fun record(outcome: BreakOutcome) {
        val all = outcomes + outcome
        val promotion = ExsurgeStats.promoted(outcomes, all, memory.archivedLaurels)
        val trimmed = all.size - maxOutcomes
        if (trimmed > 0) {
            val archived = all.take(trimmed).count { it.credited }
            memory = memory.copy(archivedLaurels = memory.archivedLaurels + archived)
            store.saveMemory(memory)
            Diag.log(
                TAG,
                "dewidebug exsurge archived $trimmed old outcome(s), $archived laurel(s); " +
                    "total archived ${memory.archivedLaurels}",
            )
        }
        outcomes = all.takeLast(maxOutcomes)
        store.saveOutcomes(outcomes)
        Diag.log(TAG, "dewidebug exsurge recorded $outcome credited=${outcome.credited}")
        promotion?.let { rank ->
            Diag.log(TAG, "dewidebug exsurge promoted to ${rank.latin}")
            if (!settings.quietOffice) ports.speak(Cue.PROMOTED, outcome.summonsId, settings.voiceVolumePercent)
        }
    }

    private fun publish(at: Instant) {
        val view = viewAt(at)
        _view.value = view
        ports.scheduleWake(view.nextWake)
        ports.viewChanged(view)
    }

    private fun viewAt(at: Instant): ExsurgeView {
        val zone = zone()
        val available = stepsAvailable
        return ExsurgeView(
            settings = settings,
            memory = memory,
            stats = ExsurgeStats.of(outcomes, at, zone, memory.archivedLaurels),
            nextWake = ExsurgeMachine.nextWake(memory.state, ExsurgeContext(settings, zone, available)),
            stepsAvailable = available,
            at = at,
            zone = zone,
        )
    }

    companion object {
        const val TAG: String = "Exsurge"
        private const val STEP_LOG_EVERY = 50L
        private const val MAX_OUTCOMES = 5000
    }
}

private fun summonsIdOf(state: ExsurgeState, fallback: Long): Long = when (state) {
    is ExsurgeState.Summoned -> state.summons.id
    is ExsurgeState.Snoozed -> state.summons.id
    is ExsurgeState.Rising -> state.summons.id
    is ExsurgeState.OnBreak -> state.summons.id
    else -> fallback
}

private fun diff(old: ExsurgeSettings, new: ExsurgeSettings): String {
    val a = old.toString().removePrefix("ExsurgeSettings(").removeSuffix(")").split(", ")
    val b = new.toString().removePrefix("ExsurgeSettings(").removeSuffix(")").split(", ")
    return b.filterIndexed { i, field -> a.getOrNull(i) != field }.joinToString(" ")
}

private fun ExsurgeEffect.label(): String = when (this) {
    is ExsurgeEffect.Speak -> "speak:$cue"
    is ExsurgeEffect.Buzz -> "buzz:$haptic"
    is ExsurgeEffect.ShowTakeover -> "show#$summonsId/$call"
    ExsurgeEffect.HideTakeover -> "hide"
    ExsurgeEffect.OpenDestination -> "open"
    ExsurgeEffect.ContinueTotum -> "continueTotum"
    ExsurgeEffect.PausePlayback -> "pause"
    ExsurgeEffect.ResumePlayback -> "resume"
    is ExsurgeEffect.Record -> "record:${outcome.kind}"
}
