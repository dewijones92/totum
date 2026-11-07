package com.dewijones92.totum.trip

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.reminders.kit.JsonPrefs
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TripSettings(
    val departureAtMs: Long? = null,
    val perGroup: Int = DEFAULT_PER_GROUP,
    val attempts: Map<String, Int> = emptyMap(),
) {
    val armed: Boolean get() = departureAtMs != null

    companion object {
        const val DEFAULT_PER_GROUP = 5
        const val MAX_PER_GROUP = 20
    }
}

object TripCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(settings: TripSettings): String = json.encodeToString(TripSettings.serializer(), settings)

    fun decode(text: String): TripSettings = json.decodeFromString(TripSettings.serializer(), text)
}

interface TripStore {
    fun read(): TripSettings
    fun write(settings: TripSettings)
}

class InMemoryTripStore(private var settings: TripSettings = TripSettings()) : TripStore {
    override fun read(): TripSettings = settings

    override fun write(settings: TripSettings) {
        this.settings = settings
    }
}

class SharedPrefsTripStore(context: Context) : TripStore {
    private val prefs = JsonPrefs(context.applicationContext, "totum_trip_mode", "trip")

    override fun read(): TripSettings = prefs.read(KEY, TripSettings(), TripCodec::decode)

    override fun write(settings: TripSettings) = prefs.write(KEY, TripCodec.encode(settings))

    private companion object {
        const val KEY = "settings"
    }
}

data class TripProgress(
    val wanted: Int = 0,
    val onPhone: Int = 0,
    val fetching: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val fromPi: Int = 0,
    val lowSpace: Boolean = false,
)

sealed interface TripStatus {
    data object Off : TripStatus

    data class On(
        val departureAtMs: Long,
        val perGroup: Int,
        val progress: TripProgress?,
        val waitingForWifi: Boolean,
    ) : TripStatus
}

@Suppress("LongParameterList")
class TripPorts(
    val queue: StateFlow<QueueSnapshot>,
    val changes: Flow<Unit>,
    val states: suspend () -> Map<MediaItemId, DownloadState>,
    val unmetered: () -> Boolean,
    val finished: (PlayableItem) -> Boolean,
    val fetchesAudioOnly: (PlayableItem) -> Boolean,
    val atHome: (PlayableItem) -> Boolean,
    val choice: () -> DownloadChoice,
    val piFiles: () -> List<PiFile>,
    val copyFromPi: (MediaItemId) -> Unit,
    val freeBytes: () -> Long?,
    val fetch: (title: String, items: List<PlayableItem>, choice: DownloadChoice) -> Unit,
    val batchRunning: () -> Boolean,
    val batchCancelled: Flow<Unit>,
    val batchTitle: String,
)

class TripMode(
    private val store: TripStore,
    private val ports: TripPorts,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = TICK_MS,
) {
    private val saved = MutableStateFlow(store.read())
    val settings: StateFlow<TripSettings> = saved.asStateFlow()

    private val state = MutableStateFlow<TripStatus>(TripStatus.Off)
    val status: StateFlow<TripStatus> = state.asStateFlow()

    private val passing = Mutex()
    private val attempts = saved.value.attempts.mapKeysTo(mutableMapOf()) { MediaItemId(it.key) }
    private val explained = mutableSetOf<MediaItemId>()
    private var watching: Job? = null
    private val said = mutableMapOf<String, String>()
    private var lastProgress: TripProgress? = null

    val diagnostics: Map<String, String>
        get() = mapOf(
            "trip.armed" to saved.value.armed.toString(),
            "trip.departureAtMs" to (saved.value.departureAtMs?.toString() ?: "none"),
            "trip.perGroup" to saved.value.perGroup.toString(),
            "trip.progress" to (lastProgress?.toString() ?: "none"),
        )

    fun start() {
        val now = saved.value
        val departure = now.departureAtMs ?: return Diag.log(TAG, "trip mode is off at launch")
        if (departure <= clock()) {
            Diag.log(TAG, "trip mode was armed for $departure but that time has passed; switching it off")
            return clear("the departure time had passed while the app was closed")
        }
        Diag.log(TAG, "trip mode resumes at launch: leaving at $departure, ${now.perGroup} per group")
        state.value = TripStatus.On(departure, now.perGroup, null, waitingForWifi = false)
        watch()
    }

    fun arm(departureAtMs: Long, perGroup: Int, why: String) {
        val count = perGroup.coerceIn(1, TripSettings.MAX_PER_GROUP)
        val minutes = (departureAtMs - clock()) / MS_PER_MINUTE
        if (departureAtMs <= clock()) {
            return Diag.warn(TAG, "arm from $why refused: $departureAtMs is not in the future")
        }
        attempts.clear()
        explained.clear()
        said.clear()
        lastProgress = null
        update(TripSettings(departureAtMs, count))
        state.value = TripStatus.On(departureAtMs, count, null, waitingForWifi = false)
        Diag.log(TAG, "trip mode armed from $why: leaving in $minutes min ($departureAtMs), $count per group")
        watch()
    }

    fun disarm(why: String) {
        if (!saved.value.armed) return
        val summary = lastProgress
        Diag.log(TAG, "trip mode off ($why); the last pass said ${summary ?: "nothing"}")
        clear(why)
    }

    suspend fun passNow(why: String): String = pass(why)

    private fun clear(why: String) {
        watching?.cancel()
        watching = null
        update(TripSettings(null, saved.value.perGroup))
        state.value = TripStatus.Off
        Diag.log(TAG, "trip mode cleared: $why")
    }

    private fun update(settings: TripSettings) {
        saved.value = settings
        store.write(settings)
    }

    private fun watch() {
        watching?.cancel()
        watching = scope.launch {
            launch { ports.batchCancelled.collect { disarm("cancelled from the progress strip") } }
            merge(ports.changes, ticks()).collectLatest { pass("a change or the minute tick") }
        }
    }

    private fun ticks(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(tickMs)
        }
    }

    private suspend fun pass(why: String): String = passing.withLock {
        val now = clock()
        val current = saved.value
        val departure = current.departureAtMs ?: return@withLock "not armed"
        if (now >= departure) {
            disarm("the departure time came")
            return@withLock "departure time came"
        }
        if (!ports.unmetered()) {
            state.value = TripStatus.On(departure, current.perGroup, lastProgress, waitingForWifi = true)
            sayOnce("wifi", "waiting: this network is metered, so the trip fetches nothing until Wi-Fi", why)
            return@withLock "waiting for Wi-Fi"
        }
        val plan = planTrip(inputsFor(current))
        act(plan, why)
        val progress = progressOf(plan)
        lastProgress = progress
        state.value = TripStatus.On(departure, current.perGroup, progress, waitingForWifi = false)
        sayOnce("pass", summaryLine(plan, progress), why)
        "${progress.onPhone} of ${progress.wanted} on the phone"
    }

    private suspend fun inputsFor(current: TripSettings) = TripInputs(
        queue = ports.queue.value,
        perGroup = current.perGroup,
        states = ports.states(),
        attempts = attempts.toMap(),
        piFiles = ports.piFiles(),
        freeBytes = ports.freeBytes(),
        finished = ports.finished,
        fetchesAudioOnly = ports.fetchesAudioOnly,
        atHome = ports.atHome,
    )

    private fun act(plan: TripPlan, why: String) {
        plan.skipped.forEach { skipped ->
            if (explained.add(skipped.item.item.id)) {
                Diag.log(TAG, "trip: not fetching \"${skipped.item.item.title}\": ${skipped.why}")
            }
        }
        if (plan.fetch.isNotEmpty()) {
            if (ports.batchRunning()) {
                sayOnce("batch", "a trip batch is still running; the ${plan.fetch.size} more wait for it", why)
            } else {
                plan.fetch.forEach { attempts.merge(it.item.id, 1, Int::plus) }
                Diag.log(
                    TAG,
                    "trip: asking for ${plan.fetch.size} download(s) as ${ports.choice().stored}: " +
                        plan.fetch.joinToString { "\"${it.item.title}\"" },
                )
                ports.fetch(ports.batchTitle, plan.fetch, ports.choice())
            }
        }
        plan.copyFromPi.forEach {
            attempts.merge(it, 1, Int::plus)
            ports.copyFromPi(it)
        }
        plan.piExtras.forEach {
            attempts.merge(it.id, 1, Int::plus)
            ports.copyFromPi(it.id)
        }
        attempts.mapKeys { it.key.value }.takeIf { it != saved.value.attempts }?.let {
            update(saved.value.copy(attempts = it))
        }
        if (plan.copyFromPi.isNotEmpty() || plan.piExtras.isNotEmpty()) {
            Diag.log(
                TAG,
                "trip: copying ${plan.copyFromPi.size} queued and ${plan.piExtras.size} other file(s) from the Pi " +
                    "(the Pi's own copy-now path)",
            )
        }
    }

    private fun progressOf(plan: TripPlan) = TripProgress(
        wanted = plan.wanted.size,
        onPhone = plan.onPhone,
        fetching = plan.fetching + plan.fetch.size + plan.copyFromPi.size,
        failed = plan.failed,
        skipped = plan.skipped.size,
        fromPi = plan.copyFromPi.size + plan.piExtras.size,
        lowSpace = plan.lowSpace,
    )

    private fun summaryLine(plan: TripPlan, progress: TripProgress): String =
        "trip pass: ${progress.onPhone} of ${progress.wanted} wanted on the phone, " +
            "${progress.fetching} on the way, to fetch ${plan.fetch.size}, copy ${plan.copyFromPi.size} queued and " +
            "${plan.piExtras.size} other from the Pi, skipped ${plan.skipped.size}, failed ${progress.failed}, " +
            "low space=${plan.lowSpace}"

    private fun sayOnce(key: String, line: String, why: String) {
        if (said[key] == line) return
        said[key] = line
        Diag.log(TAG, "$line [$why]")
    }

    private companion object {
        const val TAG = "trip"
        const val TICK_MS = 60_000L
        const val MS_PER_MINUTE = 60_000L
    }
}
