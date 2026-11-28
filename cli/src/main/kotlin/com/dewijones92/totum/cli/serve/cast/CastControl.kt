package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.playback.cast.CastFormat
import kotlinx.coroutines.delay
import java.io.BufferedWriter
import java.io.IOException
import kotlin.concurrent.thread

@Suppress("TooManyFunctions")
internal interface CastControl {
    fun load(url: String, contentType: String, title: String, startAtMs: Long)
    fun pause(paused: Boolean)
    fun seek(positionMs: Long)
    fun volume(percent: Int)
    fun volumeRefusal(): String? = null
    fun volumePercent(): Int?
    fun stop()
    fun release()
    fun reading(): CastReading?
    var onEnded: (failed: Boolean) -> Unit
    var onLost: (reason: String) -> Unit
    suspend fun reachable(): Boolean
    val whyUnreachable: String? get() = null
    val model: String? get() = null
    val screen: CastScreen? get() = null
    val keepsAddressesPrivate: Boolean get() = false
    val format: CastFormat get() = CastFormat.DASH
    val showsPictures: Boolean get() = true
    fun captions(trackId: Int?) = Unit
    val captionTracks: List<CastTrack> get() = emptyList()
    val activeCaption: Int? get() = null
    var onTracks: () -> Unit
        get() = {}
        set(value) {}
}

@Suppress("TooManyFunctions")
internal class PyCastControl(
    private val host: String,
    private val label: String,
    private val python: String = System.getenv("TOTUM_PYTHON") ?: "python3",
    private val helper: String = System.getenv("TOTUM_CAST_HELPER") ?: "/opt/totum/chromecast_helper.py",
    private val clock: () -> Long = System::currentTimeMillis,
    private val stateFile: java.io.File? = null,
    override val format: CastFormat = CastFormat.DASH,
    private val kind: String = "Chromecast",
    override val keepsAddressesPrivate: Boolean = false,
) : CastControl {

    override var onEnded: (failed: Boolean) -> Unit = {}

    override var onLost: (reason: String) -> Unit = {}

    @Volatile private var process: Process? = null

    @Volatile private var writer: BufferedWriter? = null

    @Volatile private var latest: CastReading? = null

    @Volatile private var ready = false

    @Volatile private var loaded = false

    @Volatile private var problem: String? = null

    @Volatile private var loadedUrl: String? = null

    @Volatile private var deviceVolume: Int? = null

    @Volatile private var pictures = true

    @Volatile override var captionTracks: List<CastTrack> = emptyList()
        private set

    @Volatile override var activeCaption: Int? = null
        private set

    override var onTracks: () -> Unit = {}

    @Volatile override var model: String? = null
        private set

    override val showsPictures: Boolean get() = pictures

    @Synchronized
    private fun session(): BufferedWriter? {
        writer?.takeIf { process?.isAlive == true }?.let { return it }
        ready = false
        problem = null
        return try {
            val command = listOfNotNull(python, helper, host, stateFile?.path)
            val started = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            process = started
            writer = started.outputStream.bufferedWriter()
            Diag.log("cast-target", "$label: helper ${started.pid()} started for $host")
            thread(name = "cast-reader-$label", isDaemon = true) { read(started) }
            writer
        } catch (e: IOException) {
            Diag.warn("cast-target", "$label: helper could not start ($python $helper)", e)
            problem = "the $kind helper could not start: ${e.message}"
            null
        }
    }

    private fun read(started: Process) {
        started.inputStream.bufferedReader().forEachLine { text ->
            when (val event = CastWire.parse(text)) {
                is CastEvent.Ready -> {
                    ready = true
                    model = event.model
                    event.video?.let { pictures = it }
                    Diag.log(
                        "cast-target",
                        "$label: connected, ${event.name} (${event.model}), " +
                            "pictures ${if (pictures) "accepted" else "not accepted"}",
                    )
                }
                is CastEvent.Status -> status(event)
                is CastEvent.Volume -> {
                    deviceVolume = event.percent
                    Diag.log("cast-target", "$label: the device's volume is ${event.percent}%")
                }
                is CastEvent.Handback -> Diag.log(
                    "cast-target",
                    "$label: handed back, ${if (event.quit) "receiver app quit" else "left open"}: ${event.why}",
                )
                is CastEvent.Note -> Diag.log("cast-target", "$label: helper note: ${event.message}")
                is CastEvent.Tracks -> tracks(event)
                is CastEvent.Connection -> connection(event.state)
                is CastEvent.Failure -> {
                    problem = event.message
                    Diag.warn("cast-target", "$label: ${event.message}")
                }
                null -> Diag.log("cast-target", "$label: unreadable helper line: ${text.take(LOGGED)}")
            }
        }
        Diag.log("cast-target", "$label: helper ${started.pid()} ended, exit ${started.waitFor()}")
        if (process === started) process = null
    }

    private fun tracks(event: CastEvent.Tracks) {
        captionTracks = event.tracks
        activeCaption = event.active.firstOrNull()
        val offered = event.tracks.joinToString { "${it.id}=${it.language ?: "?"}" }
        Diag.log(
            "cast-target",
            "dewidebug $label: the receiver offers ${event.tracks.size} caption track(s) [$offered], " +
                "showing ${activeCaption ?: "none"}",
        )
        onTracks()
    }

    private fun connection(state: String) {
        val lost = state in LOST_STATES
        Diag.log(
            "cast-target",
            "$label: connection $state" + if (lost) ", so the helper is dropped and Totum lets go of the TV" else ""
        )
        if (!lost) return
        ready = false
        loaded = false
        val gone = process
        process = null
        writer = null
        gone?.destroy()
        onLost("the connection to $label was $state")
    }

    private fun status(event: CastEvent.Status) {
        val playing = event.state == "PLAYING"
        latest = CastReading(event.positionMs, event.durationMs, playing, clock())
        if (event.state == "IDLE" && loaded && event.idle != null) {
            if (!endsLoaded(event)) return
            val failed = event.idle == "ERROR"
            Diag.log("cast-target", "$label: idle (${event.idle}) after ${event.positionMs}ms, failed=$failed")
            loaded = false
            onEnded(failed)
        }
    }

    private fun endsLoaded(event: CastEvent.Status): Boolean {
        val mine = loadedUrl
        val matches = if (event.contentId != null) {
            event.contentId == mine
        } else {
            event.idle == "FINISHED" || event.idle == "ERROR"
        }
        if (!matches) {
            Diag.log(
                "cast-target",
                "$label: idle (${event.idle}) for ${event.contentId ?: "no media id"} is not the item loaded " +
                    "($mine); ignored",
            )
        }
        return matches
    }

    private fun send(text: String, spawn: Boolean = false): Boolean {
        val out = (if (spawn) session() else writer?.takeIf { process?.isAlive == true }) ?: return false
        return try {
            out.write(text)
            out.newLine()
            out.flush()
            true
        } catch (e: IOException) {
            Diag.warn("cast-target", "$label: could not send to the helper", e)
            false
        }
    }

    override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
        latest = null
        captionTracks = emptyList()
        activeCaption = null
        loadedUrl = url
        loaded = true
        problem = null
        val sent = send(CastWire.load(url, contentType, title, startAtMs), spawn = true)
        Diag.log("cast-target", "$label: load sent=$sent type=$contentType from=${startAtMs}ms \"$title\"")
        if (!sent) {
            loaded = false
            onEnded(true)
        }
    }

    override fun pause(paused: Boolean) {
        send(CastWire.pause(paused))
    }

    override fun seek(positionMs: Long) {
        send(CastWire.seek(positionMs))
    }

    override fun volume(percent: Int) {
        val sent = send(CastWire.volume(percent))
        if (!sent) Diag.warn("cast-target", "$label: volume $percent% was not sent, the helper is not running")
    }

    override fun volumeRefusal(): String? =
        if (writer != null && process?.isAlive == true) {
            null
        } else {
            "no connection to $label is open, so there is nowhere to send it"
        }

    override fun volumePercent(): Int? = deviceVolume

    override fun captions(trackId: Int?) {
        val sent = send(CastWire.captions(trackId))
        Diag.log("cast-target", "dewidebug $label: captions ${trackId ?: "off"} sent=$sent")
    }

    override fun stop() {
        val wasLoaded = loaded
        loaded = false
        if (wasLoaded) {
            send(CastWire.stop())
        } else {
            Diag.log("cast-target", "$label: stop not sent, nothing is loaded from Totum")
        }
        latest = null
    }

    override fun release() {
        val sent = send(CastWire.release())
        Diag.log("cast-target", "$label: release sent=$sent (the TV quits the receiver app only if Totum launched it)")
    }

    override fun reading(): CastReading? = latest

    override suspend fun reachable(): Boolean {
        session() ?: return false
        repeat(READY_TRIES) {
            if (ready) return true
            if (problem != null) return false
            delay(READY_POLL_MS)
        }
        Diag.warn("cast-target", "$label: not connected after ${READY_TRIES * READY_POLL_MS / MS_PER_S}s")
        return false
    }

    private companion object {
        const val LOGGED = 200
        val LOST_STATES = setOf("LOST", "DISCONNECTED", "FAILED")
        const val READY_TRIES = 20
        const val READY_POLL_MS = 500L
        const val MS_PER_S = 1_000
    }
}
