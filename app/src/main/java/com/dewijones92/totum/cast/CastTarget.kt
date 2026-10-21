package com.dewijones92.totum.cast

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.CastScreen
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
    val lastSeen = AtomicReference<PlaybackState?>(null)
    container.applicationScope.launch {
        container.playbackController.state.filterNotNull().collect { lastSeen.set(it) }
    }
    container.applicationScope.launch {
        CastTarget.screens.map { it != null }.distinctUntilChanged().drop(1).collect { onTv ->
            val where = if (onTv) "the TV" else "the phone"
            val settled = withTimeoutOrNull(HANDOVER_WAIT_MS) {
                container.playbackController.state.first { it == null || it.onCastDevice == onTv }
            }
            val state = container.playbackController.state.value ?: lastSeen.get()
            if (state == null) {
                Diag.log("cast", "moved to $where with nothing played yet; nothing to replay")
                return@collect
            }
            if (state.hasEnded || state.kind == MediaKind.PODCAST) {
                Diag.log(
                    "cast",
                    "moved to $where with ${state.itemId.value} (${state.kind}, ended=${state.hasEnded}); " +
                        "nothing to replay",
                )
                return@collect
            }
            val positionMs = state.positionMs
            Diag.log(
                "cast",
                "moved to $where (player settled=${settled != null}, item=${state.itemId.value} " +
                    "at ${positionMs}ms); replaying with streams for $where " +
                    "(${CastTarget.screen?.label ?: "this phone"})",
            )
            val replayed = container.playbackQueue.replayCurrent(positionMs)
            Diag.log("cast", "replay for $where=$replayed")
        }
    }
}

private const val HANDOVER_WAIT_MS = 3_000L
