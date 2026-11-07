package com.dewijones92.totum.kidlock

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.operations.tracking
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinPlayed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface TvConnection {
    data class Connected(val device: String) : TvConnection
    data class NotFound(val seen: List<String>) : TvConnection
    data object NeedsLocalNetwork : TvConnection
    data class Failed(val why: String) : TvConnection
}

interface TvConnector {
    fun scan(): Flow<List<String>>
    suspend fun connect(tvName: String): TvConnection
}

sealed interface KidTvFailure {
    val detail: String

    data class PlaylistGone(val key: String?) : KidTvFailure {
        override val detail: String get() = "the chosen playlist $key is no longer pinned"
    }

    data class TvNotFound(val tv: String, val seen: List<String>) : KidTvFailure {
        override val detail: String get() = "$tv is not showing up as a cast target (seen: ${seen.joinToString()})"
    }

    data object NeedsLocalNetwork : KidTvFailure {
        override val detail: String get() = "Android has not given Totum local network access"
    }

    data class CastFailed(val tv: String, val why: String) : KidTvFailure {
        override val detail: String get() = "casting to $tv did not start: $why"
    }

    data class PlayFailed(val title: String, val why: String) : KidTvFailure {
        override val detail: String get() = "\"$title\" would not start: $why"
    }
}

sealed interface KidTvStatus {
    data object NotSetUp : KidTvStatus
    data object Ready : KidTvStatus
    data class Starting(val tv: String, val title: String) : KidTvStatus
    data class On(val tv: String, val title: String) : KidTvStatus
    data class Failed(val failure: KidTvFailure) : KidTvStatus
}

class KidTvPorts(
    val connector: TvConnector,
    val castDevice: StateFlow<String?>,
    val play: suspend (Pin) -> PinPlayed,
    val pause: () -> Unit,
    val endCast: () -> Unit,
    val startingLabel: (title: String, tv: String) -> String,
    val settleHandOver: suspend () -> Unit = {},
)

class KidTv(
    private val store: KidTvStore,
    private val pins: () -> List<Pin>,
    private val ports: KidTvPorts,
    private val lock: KidLock,
    private val operations: Operations?,
    private val scope: CoroutineScope,
) {
    private val chosen = MutableStateFlow(store.read())
    val choice: StateFlow<KidTvChoice> = chosen.asStateFlow()

    private val state = MutableStateFlow<KidTvStatus>(idleFor(chosen.value))
    val status: StateFlow<KidTvStatus> = state.asStateFlow()

    val scan: Flow<List<String>> get() = ports.connector.scan()

    private var starting: Job? = null
    private var watching: Job? = null

    val diagnostics: Map<String, String>
        get() = mapOf(
            "kidTv.pin" to (chosen.value.pinKey ?: "none"),
            "kidTv.tv" to (chosen.value.tvName ?: "none"),
            "kidTv.pinStillPinned" to (pinFor(chosen.value) != null).toString(),
            "kidTv.status" to state.value.toString(),
            "kidTv.castDevice" to (ports.castDevice.value ?: "none"),
        )

    init {
        Diag.log(TAG, "Kid TV loaded: ${chosen.value}; status ${state.value}")
        restoreRunning()
    }

    private fun restoreRunning() {
        val running = store.readRunning()
        val tv = running.tv?.takeIf { running.on } ?: return
        val title = running.title.orEmpty()
        state.value = KidTvStatus.Starting(tv, title)
        Diag.log(TAG, "Kid TV was on \"$title\" on $tv when the app last stopped; checking the cast is still there")
        starting = scope.launch {
            val device = withTimeoutOrNull(RECONNECT_WAIT_MS) {
                ports.castDevice.first { it != null && sameTv(it, tv) }
            }
            if (device == null) {
                Diag.warn(
                    TAG,
                    "the cast is not on $tv after ${RECONNECT_WAIT_MS}ms (casting to ${ports.castDevice.value}); " +
                        "Kid TV is off",
                )
                store.writeRunning(KidTvRunning())
                state.value = idleFor(chosen.value)
                return@launch
            }
            lock.tvOn(tv, "restored after the app restarted")
            state.value = KidTvStatus.On(tv, title)
            Diag.log(TAG, "Kid TV is still on: \"$title\" on $tv (restored, cast device $device)")
            watchCast(tv)
        }
    }

    private fun watchCast(tv: String) {
        watching = scope.launch {
            val device = ports.castDevice.first { it == null || !sameTv(it, tv) }
            Diag.log(TAG, "the cast moved off $tv (now ${device ?: "nothing"}); leaving kid TV mode")
            stop("the cast on $tv ended or moved to ${device ?: "nothing"}", endCast = false)
        }
    }

    fun choosePin(key: String?, why: String) = change(why) { it.copy(pinKey = key) }

    fun chooseTv(name: String?, why: String) = change(why) { it.copy(tvName = name) }

    fun restore(choice: KidTvChoice) = change("backup restore") { choice }

    private fun change(why: String, edit: (KidTvChoice) -> KidTvChoice) {
        val before = chosen.value
        val after = edit(before)
        if (after == before) return
        chosen.value = after
        store.write(after)
        Diag.log(TAG, "Kid TV choice from $why: $before -> $after")
        val busy = state.value is KidTvStatus.Starting || state.value is KidTvStatus.On
        if (!busy) state.value = idleFor(after)
    }

    fun tap(why: String) {
        when (val now = state.value) {
            is KidTvStatus.Starting -> Diag.log(TAG, "tap from $why ignored: already starting on ${now.tv}")
            is KidTvStatus.On -> stop("tapped from $why")
            KidTvStatus.NotSetUp -> Diag.log(TAG, "tap from $why: nothing is chosen yet (${chosen.value})")
            else -> start(why)
        }
    }

    fun start(why: String) {
        val now = chosen.value
        val pin = pinFor(now)
        val tv = now.tvName
        if (pin == null || tv.isNullOrBlank()) {
            val failure = KidTvFailure.PlaylistGone(now.pinKey).takeIf { now.pinKey != null }
            Diag.log(TAG, "start from $why refused: choice=$now pinFound=${pin != null} failure=${failure?.detail}")
            state.value = failure?.let(KidTvStatus::Failed) ?: KidTvStatus.NotSetUp
            return
        }
        if (state.value is KidTvStatus.Starting || state.value is KidTvStatus.On) {
            return Diag.log(TAG, "start from $why ignored: ${state.value}")
        }
        state.value = KidTvStatus.Starting(tv, pin.title)
        starting = scope.launch { run(pin, tv, why) }
    }

    fun stop(why: String, endCast: Boolean = true) {
        val before = state.value
        starting?.cancel()
        watching?.cancel()
        starting = null
        watching = null
        lock.tvOff(why)
        state.value = idleFor(chosen.value)
        store.writeRunning(KidTvRunning())
        Diag.log(TAG, "stopped ($why): was $before, ending the cast too=$endCast")
        if (endCast && (before is KidTvStatus.On || before is KidTvStatus.Starting)) {
            scope.launch {
                ports.pause()
                delay(PAUSE_SETTLE_MS)
                Diag.log(TAG, "paused the kid playlist; ending the cast session now")
                ports.endCast()
            }
        }
    }

    private suspend fun run(pin: Pin, tv: String, why: String) {
        var failure: KidTvFailure? = null
        var finished = false
        try {
            operations.tracking(KEY, OperationKind.TASK, ports.startingLabel(pin.title, tv)) {
                failure = connectAndPlay(pin, tv, why)
                finished = true
                failure?.let { OperationEnd.Failed(it.detail) } ?: OperationEnd.Done(1)
            }
        } finally {
            if (!finished) {
                lock.tvOff("starting was cancelled or died before it finished")
                state.value = idleFor(chosen.value)
            }
        }
        if (!finished) {
            Diag.warn(TAG, "start from $why stopped on an error before it finished")
            state.value = KidTvStatus.Failed(KidTvFailure.CastFailed(tv, "it stopped on an error"))
            return
        }
        val problem = failure
        if (problem != null) {
            Diag.warn(TAG, "start from $why failed: ${problem.detail}")
            state.value = KidTvStatus.Failed(problem)
            return
        }
        state.value = KidTvStatus.On(tv, pin.title)
        store.writeRunning(KidTvRunning(tv, pin.title))
        Diag.log(TAG, "Kid TV is on: \"${pin.title}\" on $tv (from $why)")
        watchCast(tv)
    }

    private suspend fun connectAndPlay(pin: Pin, tv: String, why: String): KidTvFailure? {
        val castingThere = ports.castDevice.value?.let { sameTv(it, tv) } == true
        Diag.log(
            TAG,
            "starting \"${pin.title}\" (${pin.key}) on $tv from $why [castDevice=${ports.castDevice.value} " +
                "castingThere=$castingThere]",
        )
        if (!castingThere) {
            when (val connection = ports.connector.connect(tv)) {
                is TvConnection.Connected -> Diag.log(TAG, "connected to ${connection.device}")
                is TvConnection.NotFound -> return KidTvFailure.TvNotFound(tv, connection.seen)
                TvConnection.NeedsLocalNetwork -> return KidTvFailure.NeedsLocalNetwork
                is TvConnection.Failed -> return KidTvFailure.CastFailed(tv, connection.why)
            }
            ports.settleHandOver()
        }
        lock.tvOn(tv, why)
        return when (val played = ports.play(pin)) {
            is PinPlayed.Started -> null
            is PinPlayed.Failed -> {
                lock.tvOff("\"${pin.title}\" would not start: ${played.why}")
                if (!castingThere) {
                    Diag.log(TAG, "ending the cast to $tv that this start opened, since nothing is playing on it")
                    ports.endCast()
                } else {
                    Diag.log(TAG, "leaving the cast to $tv as it was: it was already casting there before this start")
                }
                KidTvFailure.PlayFailed(pin.title, played.why)
            }
        }
    }

    private fun pinFor(choice: KidTvChoice): Pin? = choice.pinKey?.let { key -> pins().firstOrNull { it.key == key } }

    private fun idleFor(choice: KidTvChoice): KidTvStatus =
        if (choice.complete) KidTvStatus.Ready else KidTvStatus.NotSetUp

    private fun sameTv(a: String, b: String): Boolean = a.trim().equals(b.trim(), ignoreCase = true)

    private companion object {
        const val TAG = "kidlock"
        const val KEY = "kid-tv"
        const val PAUSE_SETTLE_MS = 800L
        const val RECONNECT_WAIT_MS = 15_000L
    }
}
