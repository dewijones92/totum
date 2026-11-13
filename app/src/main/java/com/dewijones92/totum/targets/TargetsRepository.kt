package com.dewijones92.totum.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.titleOf
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.PlayOn
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.SleepKind
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetSleepAsk
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.domain.ItemShields
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.playback.SleepFade
import com.dewijones92.totum.queue.QueueDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

@Suppress("TooManyFunctions")
class TargetsRepository(
    private val client: TargetsApi?,
    private val requester: suspend () -> Requester,
    private val colourStore: TargetColourStore,
    private val scope: CoroutineScope,
    private val operations: Operations? = null,
    private val playHere: (List<PlayableItem>, QueueMode, QueueGroup?, MediaItemId?) -> Unit = { _, _, _, _ -> },
    private val words: TargetWords? = null,
    private val sleepFade: () -> Duration = { SleepFade.DEFAULT_LENGTH },
    private val playOn: PlayOnStore = InMemoryPlayOn(),
    private val unmetered: () -> Boolean = { true },
) : QueueDestination {

    val available: Boolean get() = client != null

    private val live = MutableStateFlow<List<TargetState>>(emptyList())
    val targets: StateFlow<List<TargetState>> = live.asStateFlow()

    private val chosen = MutableStateFlow(playOn.load())
    private val homeNow = MutableStateFlow(false)

    val atHome: StateFlow<Boolean> = homeNow.asStateFlow()

    val options: StateFlow<List<PlayOnOption>> = combine(live, homeNow) { states, atHome ->
        PlayOn.options(states, atHome)
    }.stateIn(scope, SharingStarted.Eagerly, PlayOn.options(emptyList(), false))

    val selected: StateFlow<String?> = combine(chosen, options) { last, offered ->
        PlayOn.destination(last, offered)
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val playingOn: StateFlow<Map<String, Target>> = combine(live, homeNow) { states, atHome ->
        if (atHome) PlayOn.playingNow(states) else emptyMap()
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val painted = MutableStateFlow(TargetColours.assign(emptyList(), colourStore.load()))
    val colours: StateFlow<Map<String, Long>> = painted.asStateFlow()

    private val waking = ConcurrentHashMap.newKeySet<String>()

    private val trouble = MutableStateFlow<String?>(null)
    private var troubleFromRead = false
    private var failures = 0
    private var lastSummary: String? = null
    val problem: StateFlow<String?> = trouble.asStateFlow()

    fun select(id: String?, wake: Boolean = false) {
        val power = id?.let(::powerOf)
        if (wake && id != null && power == TargetPower.ASLEEP) {
            Diag.log("targets", "${targetOf(id)?.label ?: id} was picked while asleep; waking it now")
            command(id, TargetAction.WAKE)
        }
        if (chosen.value == id) return
        val was = chosen.value ?: "this phone"
        Diag.log("targets", "play on ${id ?: "this phone"} (was $was), power=$power wake=$wake")
        chosen.value = id
        playOn.save(id)
        if (!troubleFromRead) trouble.value = null
    }

    private fun destinationNow(): String? = PlayOn.destination(chosen.value, PlayOn.options(live.value, homeNow.value))

    fun troubleLine(state: TargetState): String? {
        val trouble = state.trouble
        val shaped = words
        if (trouble == null || shaped == null) return state.problem
        val title = trouble.itemId?.let { id -> state.entries.firstOrNull { it.item.itemId == id } }
            ?.let { ItemShields.titleOf(it.item) }
        return shaped.trouble(state.target.label, trouble, title)
    }

    private fun describe(answer: HomeAnswer.No): String =
        if (answer.httpCode == UNAUTHORIZED) words?.signInAgain() ?: SIGN_IN_AGAIN else answer.reason

    fun targetOf(id: String): Target? = live.value.firstOrNull { it.target.id == id }?.target

    fun recolour(id: String, colour: Long) {
        val next = painted.value + (id to colour)
        painted.value = next
        colourStore.save(next)
        Diag.log("targets", "$id recoloured to ${colour.toString(HEX)}")
    }

    suspend fun refresh(why: String): Boolean {
        val home = client ?: return false
        return when (val answer = home.targets()) {
            is HomeAnswer.No -> {
                trouble.value = describe(answer)
                troubleFromRead = true
                failures++
                if (failures >= HOME_LOST_AFTER && homeNow.value) {
                    Diag.log("targets", "home server not answering for $failures reads in a row; no longer at home")
                    homeNow.value = false
                }
                if (failures == 1 || failures % FAILURE_LOG_EVERY == 0) {
                    Diag.log("targets", "targets not read ($why), $failures in a row: ${answer.reason}")
                }
                false
            }
            is HomeAnswer.Ok -> {
                if (failures > 0) Diag.log("targets", "targets read again after $failures failed read(s)")
                failures = 0
                if (troubleFromRead) trouble.value = null
                live.value = answer.value.targets
                markHome(why)
                showWakes(answer.value.targets)
                paint(answer.value.targets.map { it.target })
                val summary = answer.value.targets.joinToString { "${it.target.label}[${it.entries.size}]" }
                if (summary != lastSummary) {
                    lastSummary = summary
                    Diag.log("targets", "read ($why): $summary")
                }
                true
            }
        }
    }

    suspend fun watchWhile(everyMs: Long = WATCH_MS) {
        while (true) {
            refresh("watching")
            delay(everyMs)
        }
    }

    suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> {
        val home = client ?: return HomeAnswer.No("❌ No home server set")
        Diag.log(
            "targets",
            "sending to $id: ${request.mode} of ${request.items.size} " +
                "[${request.items.joinToString(limit = LOGGED_IDS) { it.itemId }}] startItem=${request.startItemId} " +
                "startAtMs=${request.startAtMs} shuffle=${request.shuffle} loop=${request.loop} " +
                "repeat=${request.repeat}",
        )
        val answer = home.queue(id, request)
        logAnswer(id, "${request.mode} of ${request.items.size}", answer)
        if (answer is HomeAnswer.Ok) {
            replace(answer.value)
            markHome("$id answered")
            if (!troubleFromRead) trouble.value = null
        }
        return answer
    }

    suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
        val home = client ?: return HomeAnswer.No("❌ No home server set")
        Diag.log(
            "targets",
            "sending to $id: ${control.action} index=${control.index} itemId=${control.itemId} " +
                "itemIds=${control.itemIds?.size} positionMs=${control.positionMs} volume=${control.volume} " +
                "repeat=${control.repeat} sound=${control.sound}",
        )
        val answer = home.control(id, control)
        logAnswer(id, control.action.name, answer)
        if (answer is HomeAnswer.Ok) {
            replace(answer.value)
            markHome("$id answered")
            if (!troubleFromRead) trouble.value = null
        }
        return answer
    }

    private fun logAnswer(id: String, what: String, answer: HomeAnswer<TargetState>) {
        when (answer) {
            is HomeAnswer.Ok -> Diag.log(
                "targets",
                "$id answered $what: playing=${answer.value.playing} starting=${answer.value.starting} " +
                    "at=${answer.value.at} of ${answer.value.entries.size} trouble=${answer.value.trouble?.kind}",
            )
            is HomeAnswer.No -> Diag.warn(
                "targets",
                "$id answered $what with no: HTTP ${answer.httpCode} ${answer.reason}"
            )
        }
    }

    fun act(id: String, control: TargetControl) {
        scope.launch {
            val answer = control(id, control)
            if (answer is HomeAnswer.No) {
                trouble.value = describe(answer)
                troubleFromRead = false
                Diag.warn("targets", "$id ${control.action} failed: ${answer.reason}")
                if (answer.httpCode == CONFLICT) refresh("the queue changed under ${control.action}")
            }
        }
    }

    suspend fun requesterNow(): Requester = requester()

    private fun markHome(why: String) {
        val metered = !unmetered()
        val now = PlayOn.atHome(homeServerAnswered = true, onUnmeteredNetwork = !metered)
        if (now != homeNow.value) {
            Diag.log("targets", "at home is now $now ($why): home server answered, metered connection=$metered")
            homeNow.value = now
        }
    }

    override fun take(items: List<PlayableItem>, mode: QueueMode, group: QueueGroup?, startAt: MediaItemId?): Boolean {
        val id = destinationNow()
        if (id == null) {
            if (items.isNotEmpty()) {
                val offered = PlayOn.options(live.value, homeNow.value)
                Diag.log("targets", "play on this phone: ${PlayOn.why(chosen.value, offered, homeNow.value)}")
            }
            return false
        }
        val label = targetOf(id)?.label ?: id
        if (items.isEmpty()) return true
        Diag.log("targets", "redirecting $mode of ${items.size} to $label (the one being looked at)")
        scope.launch {
            val play = Redirected(id, label, items, mode, group, startAt)
            val run: suspend (Operations.Progress?) -> OperationEnd = { redirect(play, it) }
            operations?.track("target:$id", OperationKind.SEND_TO_TARGET, label) { run(it) } ?: run(null)
        }
        return true
    }

    internal suspend fun redirect(play: Redirected, progress: Operations.Progress? = null): OperationEnd {
        val request = TargetQueueRequest(
            items = play.items.map { it.toBackup() },
            mode = play.mode,
            groupId = play.group?.id,
            groupTitle = play.group?.title,
            from = requester(),
            startItemId = play.startAt?.value,
        )
        return when (val answer = queue(play.id, request)) {
            is HomeAnswer.No -> answeredNo(play, answer, progress)
            is HomeAnswer.Ok -> settle(play, answer.value, progress)
        }
    }

    private suspend fun answeredNo(
        play: Redirected,
        answer: HomeAnswer.No,
        progress: Operations.Progress?,
    ): OperationEnd {
        val code = answer.httpCode
        if (code != null && code < SERVER_ERROR) return fallBack(play, describe(answer), removeFromPi = false)
        Diag.warn(
            "targets",
            "${play.label} gave no clear answer to ${play.mode} of ${play.items.size} (${answer.reason}); " +
                "reading it before playing here, in case it took them",
        )
        val read = client?.targets()
        val seen = (read as? HomeAnswer.Ok)?.value?.targets?.firstOrNull { it.target.id == play.id }
        val asked = play.items.mapTo(HashSet()) { it.item.id.value }
        val holds = seen?.entries?.any { it.item.itemId in asked } == true
        if (seen != null && holds && seen.isWorkingOnIt()) {
            Diag.log(
                "targets",
                "${play.label} had taken them after all (starting=${seen.starting} playing=${seen.playing})"
            )
            replace(seen)
            return settle(play, seen, progress)
        }
        return fallBack(play, describe(answer), removeFromPi = read !is HomeAnswer.Ok || holds)
    }

    private suspend fun settle(play: Redirected, accepted: TargetState, progress: Operations.Progress?): OperationEnd {
        var state = accepted
        if (state.starting) {
            progress?.report(starting = true)
            state = awaitStart(play) ?: return fallBack(play, STOPPED_ANSWERING, removeFromPi = true)
            progress?.report(starting = false)
        }
        val asked = play.items.mapTo(HashSet()) { it.item.id.value }
        val queued = state.entries.count { it.item.itemId in asked }
        val starts = play.mode == QueueMode.PLAY || play.mode == QueueMode.REPLACE
        val line = troubleLine(state)
        Diag.log(
            "targets",
            "${play.label} answered ${play.mode} of ${play.items.size}: queued=$queued " +
                "playing=${state.playing} problem=$line",
        )
        return when {
            queued == 0 -> fallBack(play, "it queued none of them", removeFromPi = false)
            starts && !state.playing -> fallBack(play, line ?: "it did not start", removeFromPi = true)
            else -> OperationEnd.Done(queued, note = line)
        }
    }

    private suspend fun awaitStart(play: Redirected): TargetState? {
        var waited = 0L
        var misses = 0
        while (waited < START_WATCH_CAP_MS) {
            delay(START_POLL_MS)
            waited += START_POLL_MS
            if (refresh("watching ${play.label} start")) {
                misses = 0
                val state = live.value.firstOrNull { it.target.id == play.id }
                if (state != null && !state.starting) {
                    Diag.log("targets", "${play.label} finished starting after ${waited}ms: playing=${state.playing}")
                    return state
                }
            } else if (++misses >= START_WATCH_MISSES) {
                Diag.warn("targets", "${play.label}: $misses reads in a row failed while it was starting; giving up")
                return null
            }
        }
        Diag.warn("targets", "${play.label} was still starting after ${waited}ms; giving up on it")
        return live.value.firstOrNull { it.target.id == play.id }
    }

    private suspend fun fallBack(play: Redirected, why: String, removeFromPi: Boolean): OperationEnd {
        Diag.warn(
            "targets",
            "${play.label} did not take ${play.mode} of ${play.items.size} ($why); " +
                "playing them on this phone instead (taking them off ${play.label}: $removeFromPi)",
        )
        playHere(play.items, play.mode, play.group, play.startAt)
        if (removeFromPi) removeSent(play)
        val plain = why.dropWhile { !it.isLetterOrDigit() }
        return OperationEnd.Failed("⚠️ ${play.label} didn't take it ($plain). Playing on this phone instead.")
    }

    private suspend fun removeSent(play: Redirected) {
        val ids = play.items.map { it.item.id.value }
        val answer = control(play.id, TargetControl(TargetAction.REMOVE, itemIds = ids))
        if (answer is HomeAnswer.No) {
            Diag.warn(
                "targets",
                "${play.label}: could not take the ${ids.size} sent item(s) off it again: ${answer.reason}"
            )
        }
    }

    fun command(id: String, action: TargetAction) = act(id, TargetControl(action))

    fun startSleep(id: String, duration: Duration) = sleep(
        id,
        TargetSleepAsk(SleepKind.AFTER_TIME, duration.inWholeSeconds.toInt(), fadeSeconds()),
    )

    fun sleepAfterItems(id: String, itemIds: List<String>, title: String?) = sleep(
        id,
        TargetSleepAsk(SleepKind.AFTER_ITEMS, fadeSeconds = fadeSeconds(), itemIds = itemIds, title = title),
    )

    fun cancelSleep(id: String) = sleep(id, TargetSleepAsk(SleepKind.OFF))

    private fun fadeSeconds(): Int = sleepFade().inWholeSeconds.toInt()

    private fun sleep(id: String, ask: TargetSleepAsk) {
        Diag.log(
            "targets",
            "$id: sleep timer ${ask.kind} seconds=${ask.seconds} fade=${ask.fadeSeconds}s items=${ask.itemIds.size} " +
                "chosen on the phone, sent to the Pi",
        )
        act(id, TargetControl(TargetAction.SLEEP, sleep = ask))
    }

    fun playNextOn(id: String, entry: TargetEntry) {
        scope.launch {
            val request = TargetQueueRequest(
                items = listOf(entry.item),
                mode = QueueMode.NEXT,
                groupId = entry.groupId,
                groupTitle = entry.groupTitle,
                from = requester(),
            )
            Diag.log("targets", "$id: moving ${entry.item.itemId} to play next")
            val answer = queue(id, request)
            if (answer is HomeAnswer.No) {
                trouble.value = describe(answer)
                troubleFromRead = false
                Diag.warn("targets", "$id: play next of ${entry.item.itemId} failed: ${answer.reason}")
            }
        }
    }

    private fun showWakes(states: List<TargetState>) {
        val shown = operations ?: return
        states.filter { it.power == TargetPower.WAKING && waking.add(it.target.id) }.forEach { state ->
            val id = state.target.id
            Diag.log("targets", "${state.target.label} is waking; showing it on the progress strip")
            scope.launch {
                try {
                    shown.track("wake:$id", OperationKind.WAKE_TARGET, state.target.label) { awaitWake(id) }
                } finally {
                    waking.remove(id)
                }
            }
        }
    }

    private suspend fun awaitWake(id: String): OperationEnd {
        var waited = 0L
        while (powerOf(id) == TargetPower.WAKING && waited < WAKE_WATCH_CAP_MS) {
            delay(WAKE_POLL_MS)
            waited += WAKE_POLL_MS
        }
        val state = live.value.firstOrNull { it.target.id == id }
        Diag.log("targets", "$id: wake watched for ${waited}ms, power now ${state?.power}, problem=${state?.problem}")
        return when (state?.power) {
            TargetPower.ON -> OperationEnd.Done(1)
            TargetPower.ASLEEP -> OperationEnd.Failed(
                state.problem?.dropWhile { !it.isLetterOrDigit() } ?: "it did not wake"
            )
            else -> OperationEnd.Failed("the home server stopped saying whether it woke")
        }
    }

    private fun powerOf(id: String): TargetPower? = live.value.firstOrNull { it.target.id == id }?.power

    private fun replace(state: TargetState) {
        live.update { all ->
            if (all.any { it.target.id == state.target.id }) {
                all.map { if (it.target.id == state.target.id) state else it }
            } else {
                all + state
            }
        }
    }

    private fun paint(known: List<Target>) {
        val next = TargetColours.assign(known, painted.value)
        if (next != painted.value) {
            painted.value = next
            colourStore.save(next)
            Diag.log("targets", "colours now ${next.entries.joinToString { "${it.key}=${it.value.toString(HEX)}" }}")
        }
    }

    private companion object {
        const val WATCH_MS = 2_000L
        const val WAKE_POLL_MS = 500L
        const val WAKE_WATCH_CAP_MS = 45_000L
        const val FAILURE_LOG_EVERY = 30
        const val HOME_LOST_AFTER = 2
        const val CONFLICT = 409
        const val UNAUTHORIZED = 401
        const val SERVER_ERROR = 500
        const val START_POLL_MS = 500L
        const val START_WATCH_CAP_MS = 120_000L
        const val START_WATCH_MISSES = 3
        const val LOGGED_IDS = 6
        const val SIGN_IN_AGAIN = "🔑 Sign in to the home server again"
        const val STOPPED_ANSWERING = "the home server stopped answering while it started"
        const val HEX = 16
    }
}

private fun TargetState.isWorkingOnIt(): Boolean = starting || playing

internal data class Redirected(
    val id: String,
    val label: String,
    val items: List<PlayableItem>,
    val mode: QueueMode,
    val group: QueueGroup?,
    val startAt: MediaItemId?,
)
