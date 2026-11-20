package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.cast.RendererSnapshot
import com.dewijones92.totum.cli.serve.cast.RendererState
import com.dewijones92.totum.cli.serve.cast.RendererWatch
import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.cli.serve.filing.JellyfinReply
import com.dewijones92.totum.cli.serve.targets.FoundTarget
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.cli.serve.targets.TargetKit
import com.dewijones92.totum.cli.serve.targets.TargetLink
import com.dewijones92.totum.cli.serve.targets.TargetPlayer
import com.dewijones92.totum.cli.serve.targets.TargetSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.jellyfin.jellyfinId
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPlays
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TargetTrouble
import com.dewijones92.totum.data.targets.TroubleKind
import com.dewijones92.totum.domain.MediaItemId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private const val TAG = "jellyfin-client"

private fun sameItem(a: String?, b: String?): Boolean =
    a != null && b != null && a.replace("-", "").equals(b.replace("-", ""), ignoreCase = true)

internal class JellyfinClientPlayer(
    private val label: String,
    private val client: JellyfinClient,
    private val board: SessionBoard,
    private val api: JellyfinSessionApi,
    private val clock: () -> Long = System::currentTimeMillis,
    pollMs: Long = POLL_MS,
    polls: Boolean = true,
) : TargetPlayer {

    private val watch = RendererWatch(label, clock, pollMs, polls, look = ::look, seekNow = ::seekNow)

    @Volatile private var loadedId: String? = null

    @Volatile private var failureText: String? = null

    @Volatile private var deviceVolume: Int? = null

    override val plays: TargetPlays get() = TargetPlays.JELLYFIN_ONLY

    override var onEnded: (failed: Boolean) -> Unit
        get() = watch.onEnded
        set(value) {
            watch.onEnded = value
        }

    override var onLost: (reason: String) -> Unit
        get() = watch.onLost
        set(value) {
            watch.onLost = value
        }

    override val failure: String? get() = failureText

    override fun play(request: PlayRequest) {
        watch.beforeLoad()
        failureText = null
        val itemId = request.itemId?.let { MediaItemId(it).jellyfinId() }
        if (itemId == null) {
            fail("${request.itemId ?: "that item"} is not a Jellyfin item, and a Jellyfin app can play nothing else")
            return
        }
        val session = try {
            client.best(board.sessions(0))
        } catch (e: IOException) {
            fail("Jellyfin did not answer (${e.message})")
            return
        }
        if (session == null || !session.controllable) {
            fail("the Jellyfin app on $label is not open")
            return
        }
        loadedId = itemId
        val reply = api.play(session.id, itemId, request.startAtMs)
        if (reply == null || !reply.ok) {
            loadedId = null
            fail("Jellyfin answered ${reply?.code ?: "nothing"} when asked to play it on $label")
            return
        }
        watch.loaded(0)
        Diag.log(
            TAG,
            "$label: PlayNow of ${request.title} ($itemId) from=${request.startAtMs}ms sent to $session; " +
                "the client's own state is watched from here",
        )
    }

    fun poll() = watch.poll()

    private fun fail(reason: String) {
        failureText = reason
        Diag.warn(TAG, "$label: could not play: $reason")
        watch.refused()
    }

    private fun look(): RendererSnapshot {
        val session = client.best(board.sessions())
            ?: throw IOException("Jellyfin no longer lists $label")
        if (!session.controllable) throw IOException("$label is not being controlled any more (its app was closed)")
        deviceVolume = session.volume ?: deviceVolume
        val held = watch.reading?.positionMs ?: 0L
        val mine = loadedId
        return when {
            session.nowPlayingId == null ->
                RendererSnapshot(RendererState.OVER, held, null, detail = "playing nothing")
            mine != null && !sameItem(session.nowPlayingId, mine) -> RendererSnapshot(
                RendererState.OVER,
                held,
                null,
                detail = "playing ${session.nowPlayingName ?: session.nowPlayingId} instead of $mine",
            )
            else -> RendererSnapshot(
                if (session.paused) RendererState.PAUSED else RendererState.PLAYING,
                session.positionMs,
                session.durationMs,
                detail = "playing ${session.nowPlayingName ?: mine} at ${session.positionMs}ms",
            )
        }
    }

    private fun command(what: String, act: (String) -> JellyfinReply?) {
        val session = client.best(board.cached())
        if (session == null || !session.controllable) {
            Diag.log(TAG, "$label: $what not sent, its Jellyfin app is not open")
            return
        }
        val reply = act(session.id)
        if (reply == null || !reply.ok) Diag.warn(TAG, "$label: $what was refused (${reply?.code ?: "no answer"})")
    }

    private fun seekNow(positionMs: Long) = command("seek to ${positionMs}ms") { id ->
        api.playstate(id, "Seek", positionMs)
    }

    override fun pause(paused: Boolean) {
        if (!watch.isLoaded) {
            Diag.log(TAG, "$label: ${if (paused) "pause" else "resume"} not sent, nothing is loaded from Totum")
            return
        }
        command(if (paused) "pause" else "resume") { id -> api.playstate(id, if (paused) "Pause" else "Unpause") }
    }

    override fun seek(positionMs: Long) {
        watch.moved(positionMs)
        seekNow(positionMs)
    }

    override fun volume(percent: Int) {
        command("volume $percent%") { id -> api.setVolume(id, percent) }
        deviceVolume = percent.coerceIn(0, MAX_PERCENT)
    }

    override val volumePercent: Int? get() = deviceVolume

    override fun stop() {
        if (!watch.stopped()) {
            Diag.log(TAG, "$label: stop not sent, nothing is loaded from Totum")
            return
        }
        loadedId = null
        command("stop") { id -> api.playstate(id, "Stop") }
    }

    override val positionMs: Long? get() = watch.reading?.positionAt(clock())

    override val durationMs: Long? get() = watch.reading?.durationMs

    private companion object {
        const val POLL_MS = 1_000L
        const val MAX_PERCENT = 100
    }
}

internal class JellyfinClientLink(
    private val label: String,
    private val client: JellyfinClient,
    private val board: SessionBoard,
) : TargetLink {

    @Volatile override var unreachable: TargetTrouble? = null
        private set

    override val switchedOnByHand: Boolean get() = true

    override val power: TargetPower get() = if (openNow()) TargetPower.ON else TargetPower.ASLEEP

    private fun openNow(): Boolean = client.best(board.cached())?.controllable == true

    override suspend fun connect(): String? {
        unreachable = null
        val sessions = try {
            withContext(Dispatchers.IO) { board.sessions(0) }
        } catch (e: IOException) {
            Diag.warn(TAG, "$label: Jellyfin could not be asked who is connected: ${e.message}")
            unreachable = TargetTrouble(TroubleKind.NOT_CONNECTED)
            return null
        }
        val session = client.best(sessions)
        if (session?.controllable == true) {
            Diag.log(TAG, "$label: its Jellyfin app is open and controllable ($session)")
            return session.deviceName
        }
        Diag.warn(
            TAG,
            "$label: no open Jellyfin app answers to $client; Jellyfin lists " +
                "${sessions.size} session(s), matching: ${sessions.count(client::matches)}",
        )
        unreachable = TargetTrouble(TroubleKind.SWITCH_ON)
        return null
    }

    override suspend fun release() = Unit

    override suspend fun look() {
        try {
            withContext(Dispatchers.IO) { board.sessions(LOOK_FRESH_MS) }
        } catch (e: IOException) {
            Diag.warn(TAG, "$label: could not refresh Jellyfin's sessions: ${e.message}")
        }
    }

    override suspend fun present(): Boolean = openNow()

    private companion object {
        const val LOOK_FRESH_MS = 5_000L
    }
}

internal class JellyfinClientSource(
    private val clients: List<JellyfinClient>,
    private val board: SessionBoard,
    private val api: JellyfinSessionApi,
    private val clock: () -> Long = System::currentTimeMillis,
) : TargetSource {

    override suspend fun find(fresh: Boolean): List<FoundTarget> {
        val seen = try {
            withContext(Dispatchers.IO) { board.sessions(0) }
        } catch (e: IOException) {
            Diag.warn(TAG, "Jellyfin could not be asked for its sessions: ${e.message}")
            emptyList()
        }
        Diag.log(
            TAG,
            "${clients.size} configured Jellyfin client(s): " + clients.joinToString { client ->
                "${client.label} -> " + (client.best(seen)?.let { "open=${it.controllable}" } ?: "no session")
            },
        )
        return clients.map { client ->
            FoundTarget(Target(client.id, client.label, TargetKind.TV)) {
                TargetKit(
                    JellyfinClientPlayer(client.label, client, board, api, clock),
                    JellyfinClientLink(client.label, client, board),
                )
            }
        }
    }
}

internal fun jellyfinClientsFrom(
    jellyfin: Jellyfin?,
    env: (String) -> String? = System::getenv,
): TargetSource {
    val clients = JellyfinClient.fromSpec(env("TOTUM_JELLYFIN_CLIENTS"))
    if (jellyfin == null || clients.isEmpty()) {
        Diag.log(
            TAG,
            "no Jellyfin clients are targets (Jellyfin configured=${jellyfin != null}, " +
                "TOTUM_JELLYFIN_CLIENTS names ${clients.size})",
        )
        return TargetSource { emptyList() }
    }
    val api = JellyfinSessionApi(jellyfin)
    return JellyfinClientSource(clients, SessionBoard(api), api)
}
