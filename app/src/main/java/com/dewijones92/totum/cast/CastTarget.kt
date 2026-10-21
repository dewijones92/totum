package com.dewijones92.totum.cast

import android.content.Context
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.MediaKind
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

object CastTarget {

    @Volatile
    var screen: CastScreen? = null
        private set

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
        screen = chosen
        Diag.log("cast", "session $how on ${session.castDevice?.friendlyName}: formats for ${chosen.label}")
    }

    private fun stopped(how: String) {
        if (screen != null) Diag.log("cast", "session $how: formats are the phone's again")
        screen = null
    }
}

fun startCastScreens(container: AppContainer, context: Context) {
    CastTarget.watch(context)
    container.applicationScope.launch {
        var onTv: Boolean? = null
        container.playbackController.state.filterNotNull().collect { state ->
            val was = onTv
            onTv = state.onCastDevice
            val moved = was != null && was != state.onCastDevice
            if (!moved || state.hasEnded || state.kind != MediaKind.VIDEO) return@collect
            val where = if (state.onCastDevice) "the TV" else "the phone"
            Diag.log(
                "cast",
                "${state.itemId.value} moved to $where at ${state.positionMs}ms; " +
                    "replaying it with streams for $where (${CastTarget.screen?.label ?: "this phone"})",
            )
            val replayed = container.playbackQueue.replayCurrent(state.positionMs)
            Diag.log("cast", "${state.itemId.value} replay for $where=$replayed")
        }
    }
}
