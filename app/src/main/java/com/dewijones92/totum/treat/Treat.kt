package com.dewijones92.totum.treat

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.pins.Pin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface TreatContentResult {
    data class Found(val content: TreatContent) : TreatContentResult
    data class Failed(val why: String) : TreatContentResult
}

sealed interface TreatFailure {
    val detail: String

    data class PlaylistGone(val key: String?) : TreatFailure {
        override val detail: String get() = "the chosen treat $key is no longer pinned"
    }

    data class NothingToPlay(val title: String, val why: String) : TreatFailure {
        override val detail: String get() = "\"$title\" has nothing to play: $why"
    }

    data class TvNotFound(val tv: String, val seen: List<String>) : TreatFailure {
        override val detail: String get() = "$tv is not listed by the home server (seen: ${seen.joinToString()})"
    }

    data class WontTake(val tv: String, val why: String) : TreatFailure {
        override val detail: String get() = "$tv would not play it: $why"
    }

    data class TimerNotSet(val tv: String, val why: String) : TreatFailure {
        override val detail: String get() = "the timer on $tv could not be set ($why), so playback was stopped"
    }
}

sealed interface TreatStatus {
    data object NotSetUp : TreatStatus
    data object Ready : TreatStatus
    data class Starting(val title: String, val tv: String) : TreatStatus
    data class On(
        val title: String,
        val tv: String,
        val endsAtMs: Long,
        val end: TreatEnd,
        val fellBack: Boolean,
    ) : TreatStatus
    data class Failed(val failure: TreatFailure) : TreatStatus
}

interface TreatNotice {
    fun on(title: String, tv: String, endsAtMs: Long)

    fun off(why: String)

    companion object {
        val NONE = object : TreatNotice {
            override fun on(title: String, tv: String, endsAtMs: Long) = Unit

            override fun off(why: String) = Unit
        }
    }
}

@Suppress("LongParameterList")
class TreatPorts(
    val targets: StateFlow<List<TargetState>>,
    val fadeSeconds: () -> Int,
    val content: suspend (Pin) -> TreatContentResult,
    val play: suspend (targetId: String, content: TreatContent) -> HomeAnswer<TargetState>,
    val awaitStarted: suspend (targetId: String) -> TargetState?,
    val arm: suspend (targetId: String, ask: TargetSleepAsk) -> HomeAnswer<TargetState>,
    val stopNow: suspend (targetId: String) -> HomeAnswer<TargetState>,
    val subject: (title: String, tv: String) -> String,
    val detail: (minutesLeft: Int, end: TreatEnd) -> String,
    val notice: TreatNotice = TreatNotice.NONE,
)

@Suppress("TooManyFunctions")
class Treat(
    private val store: TreatStore,
    private val pins: () -> List<Pin>,
    private val ports: TreatPorts,
    private val operations: Operations?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val chosen = MutableStateFlow(store.read())
    val choice: StateFlow<TreatChoice> = chosen.asStateFlow()

    private val state = MutableStateFlow<TreatStatus>(idleFor(chosen.value))
    val status: StateFlow<TreatStatus> = state.asStateFlow()

    private var job: Job? = null

    val diagnostics: Map<String, String>
        get() = mapOf(
            "treat.pin" to (chosen.value.pinKey ?: "none"),
            "treat.tv" to (chosen.value.targetId ?: "none"),
            "treat.minutes" to chosen.value.minutes.toString(),
            "treat.end" to chosen.value.end.name,
            "treat.fade" to chosen.value.fade.toString(),
            "treat.status" to state.value.toString(),
        )

    init {
        Diag.log(TAG, "dewidebug treat loaded: ${chosen.value}; status ${state.value}")
    }

    fun resume() {
        val running = store.readRunning()
        val id = running.targetId?.takeIf { running.on } ?: return
        val tv = running.targetLabel ?: id
        val title = running.title.orEmpty()
        val left = running.endsAtMs - clock()
        if (left <= 0) {
            Diag.log(
                TAG,
                "dewidebug treat \"$title\" on $tv ended ${-left / MS_PER_S}s ago while the app was away; clearing it",
            )
            store.writeRunning(TreatRunning())
            return
        }
        state.value = TreatStatus.On(title, tv, running.endsAtMs, running.end, fellBack = false)
        Diag.log(
            TAG,
            "dewidebug treat \"$title\" on $tv still has ${left / MS_PER_S}s to run; watching the Pi's timer again",
        )
        job = scope.launch {
            val mine = coroutineContext[Job]
            var finished = false
            try {
                operations.tracking(KEY, OperationKind.TREAT, ports.subject(title, tv)) { progress ->
                    watching(id, title, tv, running.endsAtMs, running.end, clock(), restored = true) {
                        progress?.detail(it)
                    }.also { finished = true }
                }
            } finally {
                if (!finished) cancelled(mine, title, tv)
            }
            if (finished) over(title, tv)
        }
    }

    fun choose(next: TreatChoice, why: String) {
        val after = next.clamped
        val before = chosen.value
        if (after == before) return
        chosen.value = after
        store.write(after)
        Diag.log(TAG, "dewidebug treat defaults saved from $why: $before -> $after")
        val idle = state.value.let { it is TreatStatus.NotSetUp || it is TreatStatus.Ready || it is TreatStatus.Failed }
        if (idle) state.value = idleFor(after)
    }

    fun restore(restored: TreatChoice) = choose(restored, "a backup restore")

    fun tap(why: String) {
        when (val now = state.value) {
            is TreatStatus.Starting ->
                Diag.log(TAG, "dewidebug treat tap from $why ignored: already starting on ${now.tv}")
            is TreatStatus.On -> stop("tapped from $why")
            TreatStatus.NotSetUp ->
                Diag.log(TAG, "dewidebug treat tap from $why: nothing is chosen yet (${chosen.value})")
            else -> start(why)
        }
    }

    fun start(why: String, override: TreatChoice? = null) {
        val now = (override ?: chosen.value).clamped
        val pin = now.pinKey?.let { key -> pins().firstOrNull { it.key == key } }
        val targetId = now.targetId
        if (pin == null || targetId.isNullOrBlank()) {
            val failure = TreatFailure.PlaylistGone(now.pinKey).takeIf { now.pinKey != null }
            Diag.log(TAG, "dewidebug treat start from $why refused: choice=$now pinFound=${pin != null}")
            state.value = failure?.let(TreatStatus::Failed) ?: TreatStatus.NotSetUp
            return
        }
        if (state.value is TreatStatus.Starting || state.value is TreatStatus.On) {
            return Diag.log(TAG, "dewidebug treat start from $why ignored: ${state.value}")
        }
        val label = ports.targets.value.firstOrNull { it.target.id == targetId }?.target?.label
            ?: now.targetLabel
            ?: targetId
        Diag.log(
            TAG,
            "dewidebug treat start from $why: \"${pin.title}\" on $label for ${now.minutes} min, end=${now.end} " +
                "fade=${now.fade} (${if (override != null) "this time only, defaults kept" else "saved defaults"})",
        )
        state.value = TreatStatus.Starting(pin.title, label)
        job = scope.launch { run(pin, targetId, label, now, why) }
    }

    fun stop(why: String) {
        val before = state.value
        Diag.log(TAG, "dewidebug treat stop ($why): was $before")
        job?.cancel()
        job = null
        store.writeRunning(TreatRunning())
        ports.notice.off("stopped: $why")
        state.value = idleFor(chosen.value)
    }

    private suspend fun run(pin: Pin, targetId: String, label: String, now: TreatChoice, why: String) {
        val mine = currentCoroutineContext()[Job]
        var outcome: Began? = null
        var failure: TreatFailure? = null
        val subject = ports.subject(pin.title, label)
        var finished = false
        try {
            operations.tracking(KEY, OperationKind.TREAT, subject) { progress ->
                progress?.report(starting = true)
                when (val began = begin(pin, targetId, label, now)) {
                    is Began.Failed -> {
                        failure = began.failure
                        finished = true
                        OperationEnd.Failed(began.failure.detail)
                    }
                    is Began.Started -> {
                        outcome = began
                        progress?.report(starting = false)
                        watching(targetId, pin.title, label, began.endsAtMs, began.end, clock(), restored = false) {
                            progress?.detail(it)
                        }.also { finished = true }
                    }
                }
            }
        } finally {
            if (!finished) cancelled(mine, pin.title, label)
        }
        val problem = failure
        if (problem != null) {
            Diag.warn(TAG, "dewidebug treat start from $why failed: ${problem.detail}")
            ports.notice.off("failed")
            state.value = TreatStatus.Failed(problem)
            return
        }
        if (outcome != null) over(pin.title, label)
    }

    private fun cancelled(mine: Job?, title: String, tv: String) {
        if (job !== mine) return
        Diag.log(TAG, "dewidebug treat \"$title\" on $tv was stopped from the strip, so it is reset here too")
        job = null
        store.writeRunning(TreatRunning())
        ports.notice.off("stopped from the strip")
        state.value = idleFor(chosen.value)
    }

    private fun over(title: String, tv: String) {
        if (state.value !is TreatStatus.On) return
        Diag.log(TAG, "dewidebug treat \"$title\" on $tv is over")
        job = null
        store.writeRunning(TreatRunning())
        ports.notice.off("over")
        state.value = idleFor(chosen.value)
    }

    private sealed interface Began {
        data class Started(val endsAtMs: Long, val end: TreatEnd) : Began
        data class Failed(val failure: TreatFailure) : Began
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    private suspend fun begin(pin: Pin, targetId: String, label: String, now: TreatChoice): Began {
        val live = ports.targets.value
        val target = live.firstOrNull { it.target.id == targetId }
            ?: return Began.Failed(TreatFailure.TvNotFound(label, live.map { it.target.label }))
        val content = when (val found = ports.content(pin)) {
            is TreatContentResult.Failed -> return Began.Failed(TreatFailure.NothingToPlay(pin.title, found.why))
            is TreatContentResult.Found -> found.content
        }
        if (content.items.isEmpty()) {
            return Began.Failed(TreatFailure.NothingToPlay(pin.title, "it has nothing in it"))
        }
        val refusal = content.items.firstNotNullOfOrNull { target.plays.refusal(it) }
        if (refusal != null) return Began.Failed(TreatFailure.WontTake(label, refusal))
        val plan = TreatRules.plan(now, target.canSwitchOff, ports.fadeSeconds())
        Diag.log(
            TAG,
            "dewidebug treat plan for \"${pin.title}\" on $label: ${content.items.size} item(s), " +
                "end asked=${now.end} effective=${plan.end} fellBack=${plan.fellBack} " +
                "canSwitchOff=${target.canSwitchOff} " +
                "ask=${plan.ask}",
        )
        var maybeSent = false
        var accepted = false
        try {
            maybeSent = true
            val sentAnswer = ports.play(targetId, content)
            val taken = (sentAnswer as? HomeAnswer.Ok)?.value
                ?: return Began.Failed(TreatFailure.WontTake(label, (sentAnswer as HomeAnswer.No).reason)).also {
                    Diag.log(TAG, "dewidebug treat: $label refused; nothing stopped (${it.failure.detail})")
                }
            accepted = true
            val playing = if (taken.starting) ports.awaitStarted(targetId) else taken
            if (playing == null || !playing.playing) {
                val why = playing?.problem ?: "it did not start"
                return stopped(targetId, accepted, TreatFailure.WontTake(label, why))
            }
            val armed = ports.arm(targetId, plan.ask)
            if (armed is HomeAnswer.No) {
                return stopped(targetId, accepted, TreatFailure.TimerNotSet(label, armed.reason))
            }
            val endsAt = clock() + now.clamped.minutes * MS_PER_MINUTE
            store.writeRunning(TreatRunning(targetId, label, pin.title, endsAt, plan.end))
            state.value = TreatStatus.On(pin.title, label, endsAt, plan.end, plan.fellBack)
            ports.notice.on(pin.title, label, endsAt)
            Diag.log(
                TAG,
                "dewidebug treat is on: \"${pin.title}\" on $label until $endsAt (${now.minutes} min), the Pi holds " +
                    "the timer${if (plan.fellBack) "; $label cannot be switched off, so it stops instead" else ""}",
            )
            return Began.Started(endsAt, plan.end)
        } catch (cancelled: CancellationException) {
            if (maybeSent) stopQuietly(targetId, "the start was cancelled")
            throw cancelled
        }
    }

    private suspend fun stopped(targetId: String, accepted: Boolean, failure: TreatFailure): Began.Failed {
        if (accepted) stopQuietly(targetId, failure.detail)
        return Began.Failed(failure)
    }

    private suspend fun stopQuietly(targetId: String, why: String) = withContext(NonCancellable) {
        when (val answer = ports.stopNow(targetId)) {
            is HomeAnswer.Ok ->
                Diag.log(TAG, "dewidebug treat stopped $targetId ($why): it answered playing=${answer.value.playing}")
            is HomeAnswer.No -> Diag.warn(TAG, "dewidebug treat could not stop $targetId ($why): ${answer.reason}")
        }
    }

    @Suppress("LongParameterList")
    private suspend fun watching(
        targetId: String,
        title: String,
        tv: String,
        endsAtMs: Long,
        end: TreatEnd,
        startedAtMs: Long,
        restored: Boolean,
        detail: (String) -> Unit = {},
    ): OperationEnd {
        var lastMinutes = -1
        try {
            while (true) {
                val now = clock()
                val left = endsAtMs - now
                if (left <= 0) {
                    Diag.log(TAG, "dewidebug treat \"$title\" on $tv: time is up, the Pi's timer ends it (end=$end)")
                    return OperationEnd.Done(1)
                }
                val minutes = TreatRules.minutesLeft(left)
                if (minutes != lastMinutes) {
                    lastMinutes = minutes
                    detail(ports.detail(minutes, end))
                    Diag.log(TAG, "dewidebug treat \"$title\" on $tv: $minutes min left")
                }
                val target = ports.targets.value.firstOrNull { it.target.id == targetId }
                val timerGone = target != null && target.hasNoTimer()
                if (timerGone && now - startedAtMs > TIMER_GRACE_MS && left > EARLY_MARGIN_MS) {
                    Diag.log(
                        TAG,
                        "dewidebug treat \"$title\" on $tv: the Pi has no timer armed with ${left / MS_PER_S}s " +
                            "still to run (playing=${target?.playing} restored=$restored); the treat ended early",
                    )
                    return OperationEnd.Done(1)
                }
                delay(TICK_MS)
            }
        } catch (cancelled: CancellationException) {
            Diag.log(TAG, "dewidebug treat \"$title\" on $tv: stopped from the strip or the tile; stopping the TV now")
            stopQuietly(targetId, "stopped by the person")
            throw cancelled
        }
    }

    private fun TargetState.hasNoTimer(): Boolean = sleep == null && !starting

    private fun idleFor(choice: TreatChoice): TreatStatus =
        if (choice.complete) TreatStatus.Ready else TreatStatus.NotSetUp

    private companion object {
        const val TAG = "treat"
        const val KEY = "treat"
        const val MS_PER_S = 1_000L
        const val MS_PER_MINUTE = 60_000L
        const val TICK_MS = 5_000L
        const val TIMER_GRACE_MS = 30_000L
        const val EARLY_MARGIN_MS = 15_000L
    }
}
