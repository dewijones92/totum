package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal interface DevicePower {
    val power: TargetPower
    val failure: String?
    val note: String?
    val sentWake: Boolean
    val startsApp: Boolean
    val canSwitchOff: Boolean

    suspend fun refresh()

    suspend fun answersNow(): Boolean

    suspend fun signOfLife(): String?

    fun markAsleep()

    suspend fun ensureAwake(): Boolean

    suspend fun switchOff(): String?

    fun watch(everyMs: Long = WATCH_MS)

    companion object {
        const val WATCH_MS = 5_000L
    }
}

internal interface WakeStep {
    val name: String
    val note: String? get() = null
    val repeatsEveryMs: Long? get() = null
    val startsAfterMs: Long get() = 0L

    suspend fun run(): Boolean
}

internal interface PowerOff {
    val name: String
    val available: Boolean get() = true

    suspend fun run(): String?
}

internal class SteppedPower(
    private val label: String,
    private val wakeSteps: List<WakeStep>,
    private val answers: suspend () -> Boolean,
    private val wakeFailed: String,
    private val readySteps: List<WakeStep> = emptyList(),
    private val off: PowerOff? = null,
    override val startsApp: Boolean = false,
    private val evidence: () -> String? = { null },
    private val signsOfLife: suspend () -> String? = { null },
    private val unsure: () -> Boolean = { false },
    private val timeoutMs: Long = WAKE_TIMEOUT_MS,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : DevicePower {
    @Volatile override var power: TargetPower = TargetPower.ON
        private set

    @Volatile override var failure: String? = null
        private set

    @Volatile override var note: String? = null
        private set

    @Volatile override var sentWake: Boolean = false
        private set

    override val canSwitchOff: Boolean get() = off?.available == true

    private val lock = Mutex()
    private val watching = AtomicBoolean(false)

    @Volatile private var offSettledUntil = 0L

    @Volatile private var holding = false

    override suspend fun refresh() {
        if (!lock.tryLock()) return
        try {
            val answered = answers()
            if (!answered && unsure()) {
                if (!holding) {
                    Diag.log(
                        "cast-target",
                        "$label: power check unsure" + evidence()?.let { " [$it]" }.orEmpty() +
                            "; keeping ${power.name} until it is answered or its cast port goes silent",
                    )
                }
                holding = true
                return
            }
            holding = false
            val settling = answered && clock() < offSettledUntil
            val next = if (answered && !settling) TargetPower.ON else TargetPower.ASLEEP
            if (settling) Diag.log("cast-target", "$label: still answering, but it was switched off just now; ASLEEP")
            if (next != power) {
                Diag.log(
                    "cast-target",
                    "$label: ${power.name} -> ${next.name} (power check)" + evidence()?.let { " [$it]" }.orEmpty(),
                )
            }
            power = next
        } finally {
            lock.unlock()
        }
    }

    override suspend fun answersNow(): Boolean = answers()

    override suspend fun signOfLife(): String? = signsOfLife()

    override fun markAsleep() {
        if (power == TargetPower.ASLEEP) return
        Diag.log("cast-target", "$label: ${power.name} -> ASLEEP (its connection was lost)")
        power = TargetPower.ASLEEP
    }

    override suspend fun ensureAwake(): Boolean = lock.withLock {
        sentWake = false
        note = null
        if (answers()) {
            power = TargetPower.ON
            failure = null
            runReady()
            return@withLock true
        }
        if (wakeSteps.isEmpty()) {
            power = TargetPower.ASLEEP
            Diag.log("cast-target", "$label: not answering and Totum has no way to wake it, so nothing was tried")
            return@withLock true
        }
        power = TargetPower.WAKING
        failure = null
        val startedAt = clock()
        val lastRun = LongArray(wakeSteps.size) { startedAt }
        val begun = BooleanArray(wakeSteps.size) { wakeSteps[it].startsAfterMs <= 0 }
        Diag.log(
            "cast-target",
            "$label: not answering, waking it (" + wakeSteps.joinToString {
                it.name + if (it.startsAfterMs > 0) " after ${it.startsAfterMs}ms of silence" else ""
            } + ")" + evidence()?.let { " [$it]" }.orEmpty(),
        )
        sentWake = true
        wakeSteps.forEachIndexed { index, step -> if (begun[index]) runStep(step, "the wake") }
        while (clock() - startedAt < timeoutMs) {
            pause(POLL_MS)
            if (answers()) {
                power = TargetPower.ON
                note = null
                Diag.log(
                    "cast-target",
                    "$label: answered ${clock() - startedAt}ms after the wake" + evidence()?.let { " [$it]" }.orEmpty(),
                )
                runReady()
                return@withLock true
            }
            wakeSteps.forEachIndexed { index, step ->
                if (!begun[index]) {
                    if (clock() - startedAt >= step.startsAfterMs) {
                        begun[index] = true
                        lastRun[index] = clock()
                        Diag.log(
                            "cast-target",
                            "$label: silent ${lastRun[index] - startedAt}ms after the wake, now trying ${step.name}" +
                                evidence()?.let { " [$it]" }.orEmpty(),
                        )
                        runStep(step, "the wake")
                    }
                    return@forEachIndexed
                }
                val every = step.repeatsEveryMs ?: return@forEachIndexed
                if (clock() - lastRun[index] >= every) {
                    lastRun[index] = clock()
                    Diag.log(
                        "cast-target",
                        "$label: silent ${lastRun[index] - startedAt}ms after the wake, repeating ${step.name}",
                    )
                    runStep(step, "a repeat")
                }
            }
        }
        power = TargetPower.ASLEEP
        note = null
        failure = wakeFailed
        Diag.warn("cast-target", "$label: did not answer within ${timeoutMs}ms of the wake; $failure")
        false
    }

    override suspend fun switchOff(): String? = lock.withLock {
        val way = off?.takeIf { it.available }
        if (way == null) {
            Diag.warn("cast-target", "$label: switch off asked, but Totum has no way to do it")
            return@withLock "Totum has no way to switch it off"
        }
        Diag.log("cast-target", "$label: switching it off (${way.name})")
        val why = way.run()
        if (why == null) {
            power = TargetPower.ASLEEP
            offSettledUntil = clock() + OFF_SETTLE_MS
            Diag.log("cast-target", "$label: switched off (${way.name}); power is ASLEEP")
        } else {
            Diag.warn("cast-target", "$label: ${way.name} failed: $why")
        }
        why
    }

    override fun watch(everyMs: Long) {
        if (!watching.compareAndSet(false, true)) return
        thread(name = "device-power-$label", isDaemon = true) {
            while (true) {
                runCatching { runBlocking { refresh() } }
                    .onFailure { Diag.warn("cast-target", "$label: power check failed", it) }
                Thread.sleep(everyMs)
            }
        }
    }

    private suspend fun runReady() {
        readySteps.forEach { step -> runStep(step, "getting it ready") }
        note = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runStep(step: WakeStep, why: String) {
        step.note?.let { note = it }
        val ok = try {
            step.run()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("cast-target", "$label: ${step.name} threw during $why", e)
            false
        }
        if (!ok) Diag.warn("cast-target", "$label: ${step.name} did not succeed during $why")
    }

    internal companion object {
        const val WAKE_TIMEOUT_MS = 25_000L
        const val POLL_MS = 1_000L
        const val OFF_SETTLE_MS = 10_000L
    }
}
