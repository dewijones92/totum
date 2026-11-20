package com.dewijones92.totum.cast

import android.content.Context
import androidx.mediarouter.media.MediaRouter
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.QualityLabel
import com.dewijones92.totum.operations.showingEach
import com.dewijones92.totum.playback.PlaybackState
import com.dewijones92.totum.playback.cast.PlayServices
import com.dewijones92.totum.playback.cast.SessionStartWatch
import com.dewijones92.totum.queue.playForAScreen
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val deviceName = MutableStateFlow<String?>(null)

    val device: StateFlow<String?> = deviceName

    val screen: CastScreen? get() = current.value

    data class SilentStart(val device: String, val routeId: String?)

    private val silent = MutableSharedFlow<SilentStart>(extraBufferCapacity = 1)

    val silentStarts: SharedFlow<SilentStart> = silent

    @Volatile private var appContext: Context? = null

    @Volatile private var lastRoute: MediaRouter.RouteInfo? = null

    private val startWatch = SessionStartWatch { device, waitedMs -> nothingHappened(device, waitedMs) }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteSelected(
            router: MediaRouter,
            route: MediaRouter.RouteInfo,
            reason: Int,
        ) {
            val context = appContext ?: return
            val merged = runCatching { CastContext.getSharedInstance(context).mergedSelector }.getOrNull()
            if (route.isDefault || merged == null || !route.matchesSelector(merged)) return
            lastRoute = route
            Diag.log(
                "cast",
                "dewidebug the cast route ${route.name} (${route.id}) was selected (reason $reason); " +
                    "waiting ${SessionStartWatch.WAIT_MS}ms for the Cast framework to say anything about a session",
            )
            startWatch.routeSelected(route.name)
        }
    }

    private fun nothingHappened(device: String, waitedMs: Long) {
        val context = appContext
        val castContext = context?.let { runCatching { CastContext.getSharedInstance(it) }.getOrNull() }
        Diag.warn(
            "cast",
            "dewidebug the cast route $device was selected ${waitedMs}ms ago and the Cast framework has said nothing " +
                "(no session starting, started or failed) [castState=${castContext?.castState ?: "no Cast context"} " +
                "currentSession=${castContext?.sessionManager?.currentCastSession != null} " +
                "${context?.let(PlayServices::describe) ?: "no context"}]",
        )
        Diag.saveTrail("cast-drop", "route $device selected, no session callback in ${waitedMs}ms")
        silent.tryEmit(SilentStart(device, lastRoute?.id))
    }

    fun retry(context: Context, start: SilentStart) {
        val router = MediaRouter.getInstance(context)
        val route = router.routes.firstOrNull { it.id == start.routeId } ?: run {
            Diag.warn("cast", "dewidebug retry: the route ${start.device} is not on offer any more")
            return
        }
        Diag.log("cast", "dewidebug retry: selecting ${route.name} again (${route.id})")
        router.unselect(MediaRouter.UNSELECT_REASON_STOPPED)
        router.selectRoute(route)
    }

    private val listener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            startWatch.sessionCallback()
            casting(session, "started")
        }
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            startWatch.sessionCallback()
            casting(session, "resumed (was suspended=$wasSuspended)")
        }
        override fun onSessionEnded(session: CastSession, error: Int) {
            val code = CastCodes.name(error)
            Diag.log("cast", "session ENDED on ${deviceOf(session)}: code $code")
            if (error != CastStatusCodes.SUCCESS) Diag.saveTrail("cast-drop", "session ended with code $code")
            stopped("ended (code $code)")
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) {
            val why = CastCodes.suspendedReason(reason)
            Diag.warn("cast", "session SUSPENDED on ${deviceOf(session)}: reason $why; the TV may still be playing")
            Diag.saveTrail("cast-drop", "session suspended, reason $why")
        }
        override fun onSessionStarting(session: CastSession) {
            startWatch.sessionCallback()
            Diag.log("cast", "session starting on ${deviceOf(session)}")
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            startWatch.sessionCallback()
            val code = CastCodes.name(error)
            Diag.warn("cast", "session START FAILED on ${deviceOf(session)}: code $code")
            Diag.saveTrail("cast-drop", "session failed to start with code $code")
            stopped("failed to start (code $code)")
        }
        override fun onSessionEnding(session: CastSession) =
            Diag.log("cast", "session ending on ${deviceOf(session)}: connected=${session.isConnected}")
        override fun onSessionResuming(session: CastSession, sessionId: String) {
            startWatch.sessionCallback()
            Diag.log("cast", "session resuming on ${deviceOf(session)}")
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            startWatch.sessionCallback()
            val code = CastCodes.name(error)
            Diag.warn("cast", "session RESUME FAILED on ${deviceOf(session)}: code $code")
            Diag.saveTrail("cast-drop", "session failed to resume with code $code")
            stopped("failed to resume (code $code)")
        }
    }

    private fun deviceOf(session: CastSession): String =
        "${session.castDevice?.friendlyName ?: "an unknown device"} (${session.castDevice?.modelName ?: "?"})"

    fun watch(context: Context) {
        appContext = context.applicationContext
        runCatching {
            val castContext = CastContext.getSharedInstance(context)
            val sessions = castContext.sessionManager
            sessions.currentCastSession?.let { casting(it, "already running") }
            sessions.addSessionManagerListener(listener, CastSession::class.java)
            castContext.mergedSelector?.let { MediaRouter.getInstance(context).addCallback(it, routes) }
            Diag.log("cast", "dewidebug Cast is available in the app [${PlayServices.describe(context)}]")
        }.onFailure {
            Diag.warn(
                "cast",
                "Cast is not available on this device; formats stay the phone's [${PlayServices.describe(context)}]",
                it,
            )
        }
    }

    private fun casting(session: CastSession, how: String) {
        val chosen = CastScreen.of(session.castDevice?.modelName)
        deviceName.value = session.castDevice?.friendlyName
        current.value = chosen
        Diag.log("cast", "session $how on ${session.castDevice?.friendlyName}: formats for ${chosen.label}")
    }

    private fun stopped(how: String) {
        if (current.value != null) Diag.log("cast", "session $how: formats are the phone's again")
        current.value = null
        deviceName.value = null
    }
}

fun startCastScreens(container: AppContainer, context: Context) {
    CastTarget.watch(context)
    container.applicationScope.launch {
        container.playbackController.screenWantsAnItem.collect {
            container.playbackQueue.playForAScreen(container.playbackController::playOnScreen)
        }
    }
    CastMoves(
        container
    ) { onTv -> context.getString(if (onTv) R.string.cast_moving_to_tv else R.string.cast_moving_to_phone) }
        .start()
}

private val PlaybackState.replayable: Boolean
    get() = !hasEnded && !live && kind != MediaKind.PODCAST

private class CastMoves(private val container: AppContainer, private val moving: (Boolean) -> String) {
    private val lastOnPhone = AtomicReference<PlaybackState?>(null)
    private val lastOnTv = AtomicReference<PlaybackState?>(null)
    private val states get() = container.playbackController.state

    fun start() {
        container.applicationScope.launch {
            states.filterNotNull().collect { (if (it.onCastDevice) lastOnTv else lastOnPhone).set(it) }
        }
        container.applicationScope.launch {
            CastTarget.screens.map { it != null }.distinctUntilChanged().drop(1).collect { onTv ->
                container.operations.showingEach("cast-move", moving(onTv)) { moved(onTv) }
            }
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
        return "${QualityLabel.of(playing.height, playing.codec).text}, beyond ${screen.label}"
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
