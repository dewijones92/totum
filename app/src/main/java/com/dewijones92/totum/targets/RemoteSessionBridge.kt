package com.dewijones92.totum.targets

import android.content.Context
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.barDecision
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.playback.RemoteCommand
import com.dewijones92.totum.playback.RemoteCommands
import com.dewijones92.totum.playback.RemoteFacts
import com.dewijones92.totum.playback.RemoteSessionHost
import com.dewijones92.totum.ui.queue.statusRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal object RemotePolling {

    const val PLAYING_MS: Long = 10_000L
    const val PAUSED_MS: Long = 30_000L
    private const val FRESH_FRACTION_PERCENT = 80L

    fun everyMs(facts: RemoteFacts): Long = if (facts.playing || facts.starting) PLAYING_MS else PAUSED_MS

    fun freshEnoughMs(everyMs: Long): Long = everyMs * FRESH_FRACTION_PERCENT / PERCENT

    private const val PERCENT = 100L
}

internal class RemoteSessionBridge(
    private val targets: TargetsRepository,
    private val scope: CoroutineScope,
    private val statusLine: (String, TargetState) -> String,
    private val publish: (RemoteFacts?) -> Unit = RemoteSessionHost::publish,
) {

    @Volatile
    private var shown: RemoteFacts? = null

    fun start() {
        if (!targets.available) {
            Diag.log(TAG, "dewidebug no home server is set, so there is no selected device to show on the lock screen")
            return
        }
        RemoteSessionHost.commands = RemoteCommands(::send)
        val facts = combine(targets.targets, targets.selected, targets.colours) { listed, selected, colours ->
            val decision = listed.barDecision(selected)
            val colour = decision.state?.let { TargetColours.of(colours, it.target.id) }
            decision.state?.toRemoteFacts(colour ?: TargetColours.PHONE, statusLine)
        }.distinctUntilChanged()
        scope.launch {
            facts.collect { next ->
                val before = shown
                shown = next
                if (before?.deviceId != next?.deviceId || before?.current?.id != next?.current?.id) {
                    Diag.log(
                        TAG,
                        "dewidebug lock screen and notification controls: " +
                            "${before?.deviceLabel ?: "the phone's"} -> ${next?.deviceLabel ?: "the phone's"} " +
                            "(${next?.current?.id ?: "nothing"})",
                    )
                }
                publish(next)
            }
        }
        scope.launch { facts.map { it?.let(RemotePolling::everyMs) }.distinctUntilChanged().collect(::pollWhileShown) }
    }

    private var pollJob: Job? = null

    private fun pollWhileShown(everyMs: Long?) {
        pollJob?.cancel()
        if (everyMs == null) {
            pollJob = null
            Diag.log(TAG, "dewidebug background reads stopped: nothing selected is shown on the lock screen")
            return
        }
        Diag.log(
            TAG,
            "dewidebug background reads every ${everyMs}ms while the selected device is shown " +
                "(skipped while the screen's own reads are fresher than ${RemotePolling.freshEnoughMs(everyMs)}ms)",
        )
        pollJob = scope.launch {
            var skipped = 0
            while (true) {
                delay(everyMs)
                when (targets.refreshIfOlderThan(RemotePolling.freshEnoughMs(everyMs), "the lock screen controls")) {
                    Polled.FRESH_ENOUGH -> if (++skipped % SKIP_LOG_EVERY == 0) {
                        Diag.log(TAG, "dewidebug $skipped background reads skipped so far: the screen reads already")
                    }
                    Polled.READ, Polled.FAILED -> Unit
                }
            }
        }
    }

    private fun send(command: RemoteCommand) {
        val id = shown?.deviceId
        if (id == null) {
            Diag.warn(TAG, "dewidebug $command asked from the lock screen, but no device is shown; ignored")
            return
        }
        Diag.log(TAG, "dewidebug $command from the lock screen or notification sent to $id")
        targets.act(id, command.toControl())
    }

    private companion object {
        const val TAG = "remote-session"
        const val SKIP_LOG_EVERY = 30
    }
}

fun startRemoteSession(container: AppContainer, context: Context) {
    RemoteSessionBridge(
        container.targets,
        container.applicationScope,
        statusLine = { label, state ->
            context.getString(R.string.target_bar_line, label, context.getString(state.statusRes()))
        },
    ).start()
}
