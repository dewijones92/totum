package com.dewijones92.totum.targets

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.targets.SendTo
import com.dewijones92.totum.data.targets.request
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.queue.PlaybackQueue
import com.dewijones92.totum.queue.removeAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SendToTarget(
    private val targets: TargetsRepository,
    private val queue: PlaybackQueue,
    private val controller: PlaybackController,
    private val operations: Operations,
    private val scope: CoroutineScope,
) {
    fun canSend(): Boolean = targets.available && queue.state.value.current != null

    fun send(targetId: String) {
        val snapshot = queue.state.value
        val position = controller.state.value?.positionMs ?: 0
        val plan = SendTo.plan(snapshot, position)
        val label = targets.targetOf(targetId)?.label ?: targetId
        if (plan == null) {
            Diag.log("targets", "send to $label: nothing is playing from the queue, nothing to send")
            return
        }
        Diag.log(
            "targets",
            "send to $label: ${plan.items.size} item(s) from ${plan.startItemId} at ${plan.startAtMs}ms " +
                "group=${plan.groupTitle}; the phone pauses and lets them go once the target has them",
        )
        scope.launch {
            operations.track("send:$targetId", OperationKind.SEND_TO_TARGET, label) {
                val request = plan.request(targets.requesterNow())
                when (val answer = targets.queue(targetId, request)) {
                    is HomeAnswer.No -> OperationEnd.Failed(answer.reason)
                    is HomeAnswer.Ok -> {
                        val state = answer.value
                        if (state.problem != null || !state.playing) {
                            Diag.warn(
                                "targets",
                                "send to $label: it did not start (playing=${state.playing}, " +
                                    "problem=${state.problem}); the phone keeps its queue",
                            )
                            OperationEnd.Failed(state.problem ?: "it did not start")
                        } else {
                            controller.setPlaying(false)
                            queue.removeAll(plan.toRemove.map { snapshot.entries[it].item.item.id }.toSet())
                            Diag.log("targets", "send to $label: playing there; ${plan.toRemove.size} left the phone")
                            targets.select(targetId)
                            OperationEnd.Done(plan.items.size)
                        }
                    }
                }
            }
        }
    }
}
