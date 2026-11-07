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

class TargetsRepository(
    private val client: TargetsApi?,
    private val requester: suspend () -> Requester,
    private val colourStore: TargetColourStore,
    private val scope: CoroutineScope,
    private val operations: Operations? = null,
) : QueueDestination {

    val available: Boolean get() = client != null

    private val live = MutableStateFlow<List<TargetState>>(emptyList())
    val targets: StateFlow<List<TargetState>> = live.asStateFlow()

    private val chosen = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = chosen.asStateFlow()

    private val painted = MutableStateFlow(TargetColours.assign(emptyList(), colourStore.load()))
    val colours: StateFlow<Map<String, Long>> = painted.asStateFlow()

    private val trouble = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = trouble.asStateFlow()

    fun select(id: String?) {
        if (chosen.value == id) return
        Diag.log("targets", "looking at ${id ?: "this phone"} (was ${chosen.value ?: "this phone"})")
        chosen.value = id
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
                Diag.log("targets", "targets not read ($why): ${answer.reason}")
            }
            is HomeAnswer.Ok -> {
                trouble.value = null
                live.value = answer.value.targets
                paint(answer.value.targets.map { it.target })
                Diag.log(
                    "targets",
                    "read ($why): " + answer.value.targets.joinToString { "${it.target.label}[${it.entries.size}]" },
                )
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
            val request = TargetQueueRequest(
                items = items.map { it.toBackup() },
                mode = mode,
                groupId = group?.id,
                groupTitle = group?.title,
                from = requester(),
                startItemId = startAt?.value,
            )
            val run: suspend (Operations.Progress?) -> OperationEnd = {
                when (val answer = queue(id, request)) {
                    is HomeAnswer.Ok -> OperationEnd.Done(items.size)
                    is HomeAnswer.No -> OperationEnd.Failed(answer.reason)
                }
            }
            operations?.track("target:$id", OperationKind.SEND_TO_TARGET, label) { run(it) } ?: run(null)
        }
        return true
    }

    fun command(id: String, action: TargetAction) = act(id, TargetControl(action))

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
        const val HEX = 16
    }
}
