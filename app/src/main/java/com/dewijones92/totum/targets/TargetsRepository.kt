package com.dewijones92.totum.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.targets.QueueMode
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.queue.QueueDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Suppress("TooManyFunctions")
class TargetsRepository(
    private val client: TargetsApi?,
    private val requester: suspend () -> Requester,
    private val colourStore: TargetColourStore,
    private val scope: CoroutineScope,
    private val operations: Operations? = null,
    private val playHere: (List<PlayableItem>, QueueMode, QueueGroup?, MediaItemId?) -> Unit = { _, _, _, _ -> },
) : QueueDestination {

    val available: Boolean get() = client != null

    private val live = MutableStateFlow<List<TargetState>>(emptyList())
    val targets: StateFlow<List<TargetState>> = live.asStateFlow()

    private val chosen = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = chosen.asStateFlow()

    private val painted = MutableStateFlow(TargetColours.assign(emptyList(), colourStore.load()))
    val colours: StateFlow<Map<String, Long>> = painted.asStateFlow()

    private val trouble = MutableStateFlow<String?>(null)
    private var failures = 0
    private var lastSummary: String? = null
    val problem: StateFlow<String?> = trouble.asStateFlow()

    fun select(id: String?) {
        if (chosen.value == id) return
        Diag.log("targets", "looking at ${id ?: "this phone"} (was ${chosen.value ?: "this phone"})")
        chosen.value = id
    }

    fun queueTabLeft() {
        val was = chosen.value ?: return
        Diag.log("targets", "the Queue tab was left; no longer looking at $was, so plays stay on this phone")
        chosen.value = null
    }

    fun targetOf(id: String): Target? = live.value.firstOrNull { it.target.id == id }?.target

    fun recolour(id: String, colour: Long) {
        val next = painted.value + (id to colour)
        painted.value = next
        colourStore.save(next)
        Diag.log("targets", "$id recoloured to ${colour.toString(HEX)}")
    }

    suspend fun refresh(why: String) {
        val home = client ?: return
        when (val answer = home.targets()) {
            is HomeAnswer.No -> {
                trouble.value = answer.reason
                failures++
                if (failures == 1 || failures % FAILURE_LOG_EVERY == 0) {
                    Diag.log("targets", "targets not read ($why), $failures in a row: ${answer.reason}")
                }
            }
            is HomeAnswer.Ok -> {
                if (failures > 0) Diag.log("targets", "targets read again after $failures failed read(s)")
                failures = 0
                trouble.value = null
                live.value = answer.value.targets
                paint(answer.value.targets.map { it.target })
                val summary = answer.value.targets.joinToString { "${it.target.label}[${it.entries.size}]" }
                if (summary != lastSummary) {
                    lastSummary = summary
                    Diag.log("targets", "read ($why): $summary")
                }
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
        val answer = home.queue(id, request)
        if (answer is HomeAnswer.Ok) replace(answer.value)
        return answer
    }

    suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
        val home = client ?: return HomeAnswer.No("❌ No home server set")
        val answer = home.control(id, control)
        if (answer is HomeAnswer.Ok) replace(answer.value)
        return answer
    }

    fun act(id: String, control: TargetControl) {
        scope.launch {
            val answer = control(id, control)
            if (answer is HomeAnswer.No) {
                trouble.value = answer.reason
                Diag.warn("targets", "$id ${control.action} failed: ${answer.reason}")
            }
        }
    }

    suspend fun requesterNow(): Requester = requester()

    override fun take(items: List<PlayableItem>, mode: QueueMode, group: QueueGroup?, startAt: MediaItemId?): Boolean {
        val id = chosen.value ?: return false
        val label = targetOf(id)?.label ?: id
        if (items.isEmpty()) return true
        Diag.log("targets", "redirecting $mode of ${items.size} to $label (the one being looked at)")
        scope.launch {
            val play = Redirected(id, label, items, mode, group, startAt)
            val run: suspend (Operations.Progress?) -> OperationEnd = { redirect(play) }
            operations?.track("target:$id", OperationKind.SEND_TO_TARGET, label) { run(it) } ?: run(null)
        }
        return true
    }

    internal suspend fun redirect(play: Redirected): OperationEnd {
        val request = TargetQueueRequest(
            items = play.items.map { it.toBackup() },
            mode = play.mode,
            groupId = play.group?.id,
            groupTitle = play.group?.title,
            from = requester(),
            startItemId = play.startAt?.value,
        )
        return when (val answer = queue(play.id, request)) {
            is HomeAnswer.No -> fallBack(play, answer.reason)
            is HomeAnswer.Ok -> {
                val state = answer.value
                val asked = play.items.mapTo(HashSet()) { it.item.id.value }
                val queued = state.entries.count { it.item.itemId in asked }
                val starts = play.mode == QueueMode.PLAY || play.mode == QueueMode.REPLACE
                Diag.log(
                    "targets",
                    "${play.label} answered ${play.mode} of ${play.items.size}: queued=$queued " +
                        "playing=${state.playing} problem=${state.problem}",
                )
                when {
                    queued == 0 -> fallBack(play, "it queued none of them")
                    starts && (state.problem != null || !state.playing) ->
                        fallBack(play, state.problem ?: "it did not start")
                    else -> OperationEnd.Done(queued, note = state.problem)
                }
            }
        }
    }

    private fun fallBack(play: Redirected, why: String): OperationEnd {
        Diag.warn(
            "targets",
            "${play.label} did not take ${play.mode} of ${play.items.size} ($why); playing them on this phone instead",
        )
        playHere(play.items, play.mode, play.group, play.startAt)
        val plain = why.dropWhile { !it.isLetterOrDigit() }
        return OperationEnd.Failed("⚠️ ${play.label} didn't take it ($plain). Playing on this phone instead.")
    }

    fun command(id: String, action: TargetAction) = act(id, TargetControl(action))

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
                trouble.value = answer.reason
                Diag.warn("targets", "$id: play next of ${entry.item.itemId} failed: ${answer.reason}")
            }
        }
    }

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
        const val FAILURE_LOG_EVERY = 30
        const val HEX = 16
    }
}

internal data class Redirected(
    val id: String,
    val label: String,
    val items: List<PlayableItem>,
    val mode: QueueMode,
    val group: QueueGroup?,
    val startAt: MediaItemId?,
)
