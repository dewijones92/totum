package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.targets.FoundTarget
import com.dewijones92.totum.cli.serve.targets.TargetKit
import com.dewijones92.totum.cli.serve.targets.TargetSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.playback.cast.Screen
import com.dewijones92.totum.playback.cast.ScreenHost
import com.dewijones92.totum.playback.cast.ScreenLink
import com.dewijones92.totum.playback.cast.ScreenPage
import com.dewijones92.totum.playback.cast.ScreenReport
import com.dewijones92.totum.playback.cast.StaleReports
import com.dewijones92.totum.playback.cast.quoted
import com.dewijones92.totum.playback.cast.reportIsCurrent
import com.dewijones92.totum.playback.cast.screenProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

internal class BrowserScreens(
    page: () -> ByteArray = ScreenPage::bundled,
    private val clock: () -> Long = System::currentTimeMillis,
    pollWaitMs: Long = POLL_WAIT_MS,
) {
    val link = ScreenLink(null, page, clock, pollWaitMs, ScreenHost.PI)

    private class Listener(
        val onReport: (ScreenReport) -> Unit,
        val onSeek: (Long) -> Unit,
        val onLost: (String) -> Unit,
    ) {
        @Volatile var lostTold = false
    }

    private val listeners = ConcurrentHashMap<String, Listener>()

    @Volatile
    var onChanged: () -> Unit = {}

    init {
        link.onReport = { id, report -> listeners[id]?.onReport?.invoke(report) }
        link.onSeek = { id, positionMs ->
            val listener = listeners[id]
            if (listener == null) {
                Diag.warn(TAG, "the seek bar on $id asked for ${positionMs}ms, but nothing is playing there; ignored")
            } else {
                listener.onSeek(positionMs)
            }
        }
        link.onScreensChanged = { present ->
            Diag.log(TAG, "browsers now: ${present.joinToString { "${it.name} (${it.id})" }.ifEmpty { "none" }}")
            onChanged()
        }
    }

    fun present(): List<Screen> = link.present()

    fun screenOf(id: String): Screen? = link.screen(id)

    fun hears(id: String): Boolean = link.heardWithin(id, HEARD_WITHIN_MS)

    fun send(id: String, command: String): Long = link.send(id, command)

    fun listen(id: String, onReport: (ScreenReport) -> Unit, onSeek: (Long) -> Unit, onLost: (String) -> Unit) {
        listeners[id] = Listener(onReport, onSeek, onLost)
    }

    fun keepWatching(scope: CoroutineScope, changed: () -> Unit) {
        onChanged = changed
        scope.launch {
            while (true) {
                delay(SWEEP_MS)
                sweep()
            }
        }
    }

    fun sweep() {
        listeners.forEach { (id, listener) ->
            val here = link.heardWithin(id, GONE_AFTER_MS)
            if (here) {
                listener.lostTold = false
            } else if (!listener.lostTold) {
                listener.lostTold = true
                Diag.warn(TAG, "browser $id has not been heard from for ${GONE_AFTER_MS / MS_PER_S}s; telling its deck")
                listener.onLost("the browser stopped answering")
            }
        }
    }

    private companion object {
        const val TAG = "browser-target"
        const val POLL_WAIT_MS = 20_000L
        const val HEARD_WITHIN_MS = 10_000L
        const val GONE_AFTER_MS = 30_000L
        const val SWEEP_MS = 5_000L
        const val MS_PER_S = 1_000L
    }
}

@Suppress("TooManyFunctions")
internal class BrowserControl(
    private val screenId: String,
    private val screens: BrowserScreens,
    private val clock: () -> Long = System::currentTimeMillis,
) : CastControl {

    private val stale = StaleReports(clock)
    private var loads = 0L
    private var endedFor = NONE
    private var wanted: Wanted? = null

    @Volatile private var latest: CastReading? = null

    @Volatile private var volume: Int? = null

    override var onEnded: (failed: Boolean) -> Unit = {}
    override var onLost: (reason: String) -> Unit = {}

    override val screen: CastScreen? get() = screenProfile(screens.screenOf(screenId))

    private class Wanted(val url: String, val title: String, val startAtMs: Long)

    init {
        screens.listen(screenId, ::reported, ::seekedOnThePage) { reason -> onLost(reason) }
    }

    @Synchronized
    override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
        loads++
        stale.current()
        latest = null
        wanted = Wanted(url, title, startAtMs)
        Diag.log(TAG, "$screenId: load $loads of \"$title\" from ${startAtMs}ms ($contentType)")
        sendLoad()
    }

    private fun sendLoad() {
        val now = wanted ?: return
        screens.send(
            screenId,
            """{"do":"load","load":$loads,"url":${quoted(now.url)},"pos":${now.startAtMs},"play":true,""" +
                """"rate":1,"title":${quoted(now.title)},"subs":null}""",
        )
    }

    override fun pause(paused: Boolean) {
        screens.send(screenId, if (paused) """{"do":"pause"}""" else """{"do":"play"}""")
    }

    override fun seek(positionMs: Long) {
        latest = latest?.let { CastReading(positionMs, it.durationMs, it.playing, clock()) }
        screens.send(screenId, """{"do":"seek","pos":$positionMs}""")
    }

    override fun volume(percent: Int) {
        volume = percent
        Diag.log(TAG, "$screenId: volume $percent%")
        screens.send(screenId, """{"do":"volume","level":${percent / PERCENT}}""")
    }

    override fun volumePercent(): Int? = volume

    override fun stop() {
        latest = null
        screens.send(screenId, """{"do":"stop"}""")
    }

    override fun release() = stop()

    override fun reading(): CastReading? = latest

    override suspend fun reachable(): Boolean {
        val heard = screens.hears(screenId)
        if (!heard) Diag.warn(TAG, "$screenId: not heard from in the last few seconds, so not reachable")
        return heard
    }

    @Synchronized
    private fun reported(report: ScreenReport) {
        if (!reportIsCurrent(report.load, loads)) {
            val verdict = stale.stale(report.load, loads)
            verdict.lines.forEach { Diag.log(TAG, "$screenId: $it") }
            if (verdict.resend) sendLoad()
            return
        }
        stale.current()
        report.error?.let { Diag.warn(TAG, "$screenId reports an error: $it") }
        latest = CastReading(report.positionMs, null, report.playing && !report.buffering, report.atMs)
        if (report.ended && endedFor != loads) {
            endedFor = loads
            Diag.log(TAG, "$screenId: load $loads reached its end at ${report.positionMs}ms")
            onEnded(false)
        }
    }

    private fun seekedOnThePage(positionMs: Long) {
        Diag.log(TAG, "$screenId: the page's seek bar asks for ${positionMs}ms")
        seek(positionMs)
    }

    private companion object {
        const val TAG = "browser-target"
        const val NONE = -1L
        const val PERCENT = 100.0
    }
}

internal class BrowserSource(
    private val screens: BrowserScreens,
    private val streams: PiStreams,
) : TargetSource {

    override suspend fun find(fresh: Boolean): List<FoundTarget> {
        if (!streams.available) return emptyList()
        val present = screens.present()
        val clashes = present.groupingBy { it.name }.eachCount()
        return present.map { screen ->
            val clash = (clashes[screen.name] ?: 1) > 1
            val label = if (clash) "${screen.name} ·${screen.id.takeLast(SUFFIX)}" else screen.name
            FoundTarget(Target(idOf(screen.id), label, TargetKind.BROWSER)) {
                val control = BrowserControl(screen.id, screens)
                TargetKit(ChromecastPlayer(label, control, streams), CastLink(label, control))
            }
        }
    }

    companion object {
        private const val SUFFIX = 4
        private const val ID_CHARS = 16

        fun idOf(screenId: String): String =
            "browser-" + screenId.lowercase().filter { it.isLetterOrDigit() }.takeLast(ID_CHARS)
    }
}
