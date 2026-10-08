package com.dewijones92.totum.playback

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

internal interface GaplessLine {
    fun nextUp(): PlayableItem?
    fun armedNext(): MediaItemId?
    fun notNow(): String?
    fun metered(): Boolean
    suspend fun arm(allowStream: Boolean): Boolean
    fun disarm(reason: String)
}

internal class GaplessArmer(
    private val states: StateFlow<PlaybackState?>,
    private val line: GaplessLine,
    private val scope: CoroutineScope,
    private val leadMs: Long = LEAD_MS,
) {
    private var triedFor: Pair<MediaItemId, MediaItemId>? = null

    fun start() {
        scope.launch { states.filterNotNull().collect { consider(it) } }
    }

    internal suspend fun consider(state: PlaybackState) {
        val reason = line.notNow()
        if (reason != null) {
            if (line.armedNext() != null) line.disarm(reason)
            return
        }
        val next = line.nextUp()
        takeBackIfStale(next)
        if (next != null && due(state) && fresh(state, next)) arm(state, next)
    }

    private fun takeBackIfStale(next: PlayableItem?) {
        val armed = line.armedNext() ?: return
        if (armed != next?.item?.id) line.disarm("the queue's next is now ${next?.item?.id?.value ?: "nothing"}")
    }

    private fun due(state: PlaybackState): Boolean {
        val remaining = state.durationMs?.let { it - state.positionMs } ?: return false
        return !state.hasEnded && remaining in 0..leadMs && settled(state, remaining)
    }

    private fun settled(state: PlaybackState, remaining: Long): Boolean =
        state.isPlaying && !state.isBuffering &&
            state.bufferedPositionMs - state.positionMs >= minOf(remaining, SETTLED_AHEAD_MS)

    private fun fresh(state: PlaybackState, next: PlayableItem): Boolean =
        next.item.id != state.itemId && line.armedNext() != next.item.id && triedFor != (state.itemId to next.item.id)

    private suspend fun arm(state: PlaybackState, next: PlayableItem) {
        triedFor = state.itemId to next.item.id
        val onMetered = line.metered()
        val remaining = state.durationMs?.minus(state.positionMs)
        Diag.log(
            "gapless",
            "${remaining}ms left of ${state.itemId.value} — getting ${next.item.id.value} in line (metered=$onMetered)",
        )
        if (!line.arm(!onMetered)) {
            Diag.log("gapless", "${next.item.id.value} was not put in line; the queue will advance as usual")
        }
    }

    companion object {
        const val LEAD_MS: Long = 45_000
        const val SETTLED_AHEAD_MS: Long = 10_000
    }
}

internal fun gaplessNotNow(settings: AppPreferences.Settings, sleep: SleepTimerState): String? = when {
    !settings.gaplessQueue -> "gapless queue is off in Settings"
    !settings.autoPlayNext -> "auto-play next is off"
    sleep is SleepTimerState.AfterCurrentItem -> "the sleep timer stops after this item"
    else -> null
}

internal fun startGapless(container: AppContainer, metered: () -> Boolean) {
    val controller = container.playbackController
    val queue = container.playbackQueue
    val line = object : GaplessLine {
        override fun nextUp() = queue.peekNext()
        override fun armedNext() = controller.armedNext
        override fun notNow() = gaplessNotNow(container.appPreferences.settings.value, container.sleepTimer.state.value)
        override fun metered() = metered()
        override suspend fun arm(allowStream: Boolean) = queue.armNext(allowStream)
        override fun disarm(reason: String) = controller.disarmNext(reason)
    }
    GaplessArmer(controller.state, line, container.applicationScope).start()
    container.applicationScope.launch {
        controller.events.filterIsInstance<PlaybackEvent.CrossedOver>().collect { event ->
            val adopted = queue.adoptCrossover(event.itemId)
            Diag.log(
                "advance",
                "${event.fromItemId.value} ${if (event.finished) "ended" else "was skipped"}; the player " +
                    "crossed to ${event.itemId.value} by itself -> queue adopted=$adopted",
            )
        }
    }
}
