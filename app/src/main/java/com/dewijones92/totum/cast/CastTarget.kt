package com.dewijones92.totum.cast

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.playback.PlaybackState
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

object CastTarget {

    private val current = MutableStateFlow<CastScreen?>(null)

    val screens: StateFlow<CastScreen?> = current

    val screen: CastScreen? get() = current.value

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = casting(session, "started")
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = casting(session, "resumed")
        override fun onSessionEnded(session: CastSession, error: Int) = stopped("ended (error $error)")
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStartFailed(session: CastSession, error: Int) = stopped("failed to start (error $error)")
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(
            session: CastSession,
            error: Int
        ) = stopped("failed to resume (error $error)")
    }

    fun watch(context: Context) {
        runCatching {
            val sessions = CastContext.getSharedInstance(context).sessionManager
            sessions.currentCastSession?.let { casting(it, "already running") }
            sessions.addSessionManagerListener(listener, CastSession::class.java)
        }.onFailure { Diag.warn("cast", "Cast is not available on this device; formats stay the phone's", it) }
    }

    private fun casting(session: CastSession, how: String) {
        val chosen = CastScreen.of(session.castDevice?.modelName)
        current.value = chosen
        Diag.log("cast", "session $how on ${session.castDevice?.friendlyName}: formats for ${chosen.label}")
    }

    private fun stopped(how: String) {
        if (current.value != null) Diag.log("cast", "session $how: formats are the phone's again")
        current.value = null
    }
}

fun startCastScreens(container: AppContainer, context: Context) {
    CastTarget.watch(context)
    CastMoves(container).start()
}

private val PlaybackState.replayable: Boolean
    get() = !hasEnded && !live && kind != MediaKind.PODCAST

private class CastMoves(private val container: AppContainer) {
    private val lastOnPhone = AtomicReference<PlaybackState?>(null)
    private val lastOnTv = AtomicReference<PlaybackState?>(null)
    private val states get() = container.playbackController.state

    fun start() {
        container.applicationScope.launch {
            states.filterNotNull().collect { (if (it.onCastDevice) lastOnTv else lastOnPhone).set(it) }
        }
        container.applicationScope.launch {
            CastTarget.screens.map { it != null }.distinctUntilChanged().drop(1).collect { moved(it) }
        }
    }

    private suspend fun moved(onTv: Boolean) {
        val where = if (onTv) "the TV" else "the phone"
        val before = (if (onTv) lastOnPhone else lastOnTv).get()
        if (onTv && replayedAtOnce(where, before)) return
        val settled = withTimeoutOrNull(HANDOVER_WAIT_MS) {
            states.first { it != null && it.onCastDevice == onTv && !it.castLoading }
        }
        val state = settled ?: states.value ?: before
        when {
            state == null -> Diag.log("cast", "moved to $where with nothing played yet; nothing to replay")
            !state.replayable -> Diag.log(
                "cast",
                "moved to $where with ${state.itemId.value} " +
                    "(${state.kind}, live=${state.live}, ended=${state.hasEnded}); " +
                    "nothing to replay",
            )
            onTv && alreadyRightForTheTv(state.itemId) -> Unit
            else -> replay(where, state, before, settled != null)
        }
    }

    private suspend fun replay(where: String, state: PlaybackState, before: PlaybackState?, settled: Boolean) {
        val positionMs = before?.takeIf { it.itemId == state.itemId }?.positionMs ?: state.positionMs
        Diag.log(
            "cast",
            "moved to $where (player settled=$settled, item=${state.itemId.value} at ${positionMs}ms, " +
                "${before?.positionMs}ms before the move, the new player at ${state.positionMs}ms); " +
                "replaying with streams for $where (${CastTarget.screen?.label ?: "this phone"})",
        )
        val replayed = container.playbackQueue.replayCurrent(positionMs)
        val resume = before?.wantsToPlay == true
        Diag.log(
            "cast",
            "replay for $where=$replayed; was playing before the move=${before?.wantsToPlay}, " +
                "resuming it ourselves=$resume",
        )
        if (resume) container.playbackController.setPlaying(true)
    }

    private suspend fun replayedAtOnce(where: String, before: PlaybackState?): Boolean {
        val replayable = before?.takeIf { it.replayable } ?: return false
        val beyond = streamBeyondTheTv() ?: return false
        Diag.log("cast", "${replayable.itemId.value}: the phone's stream is $beyond; replaying for the TV at once")
        replay(where, replayable, replayable, settled = false)
        return true
    }

    private fun streamBeyondTheTv(): String? {
        val screen = CastTarget.screen ?: return null
        val quality = container.videoPlaybackLauncher.quality.value
        val playing = quality.options.firstOrNull { it.id == quality.selectedId } ?: return null
        if (quality.listening || screen.canDecode(playing.codec, playing.height)) return null
        return "${playing.codec} ${playing.height}p, beyond ${screen.label}"
    }

    private suspend fun alreadyRightForTheTv(item: MediaItemId): Boolean {
        val screen = CastTarget.screen ?: return false
        val quality = container.videoPlaybackLauncher.quality.value
        if (quality.listening) {
            Diag.log("cast", "${item.value} on the TV: sound only, by choice (Listen); no replay")
            return true
        }
        val best = screen.bestOf(quality.options.map { it.codec to it.height })
        val sent = withTimeoutOrNull(SENT_WAIT_MS) {
            states.first {
                it != null && it.itemId == item && (it.castVideoHeight != null || it.castVideoRefused != null)
            }
        }
        val height = sent?.castVideoHeight
        val enough = sent?.castVideoRefused == null && height != null && height >= best
        Diag.log(
            "cast",
            "${item.value} on the TV: sent ${height?.let { "${it}p" } ?: "nothing yet"}, " +
                "refused=${sent?.castVideoRefused}, the TV goes to ${screen.maxHeight}p, " +
                "the best of ${quality.options.size} offered it can show is ${best}p -> " +
                if (enough) "already as good as it gets; no replay" else "replaying for the TV's own formats",
        )
        return enough
    }

    private companion object {
        const val HANDOVER_WAIT_MS = 15_000L
        const val SENT_WAIT_MS = 5_000L
    }
}
