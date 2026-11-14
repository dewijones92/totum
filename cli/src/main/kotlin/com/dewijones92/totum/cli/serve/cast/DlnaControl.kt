package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.CastFormat
import kotlin.concurrent.thread

@Suppress("TooManyFunctions", "LongParameterList")
internal class DlnaControl(
    private val descriptionUrl: String,
    private val label: String,
    private val http: UpnpHttp = JdkUpnpHttp(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val pollMs: Long = POLL_MS,
    private val polls: Boolean = true,
) : CastControl {

    override val format: CastFormat get() = CastFormat.HLS

    override var onEnded: (failed: Boolean) -> Unit = {}

    override var onLost: (reason: String) -> Unit = {}

    override val model: String? get() = device?.model ?: device?.friendlyName

    private val soap = UpnpSoap(http)
    private val lock = Any()

    @Volatile private var device: UpnpDevice? = null

    @Volatile private var hlsMime: String? = null

    @Volatile private var latest: CastReading? = null

    @Volatile private var deviceVolume: Int? = null

    @Volatile private var loaded = false

    @Volatile private var loadedAt = 0L

    @Volatile private var sawPlaying = false

    @Volatile private var pendingSeekMs = 0L

    @Volatile private var generation = 0

    @Volatile private var misses = 0

    @Volatile private var polling: Thread? = null

    private fun avTransport() = device?.service(UpnpDevice.AV_TRANSPORT)

    private fun rendering() = device?.service(UpnpDevice.RENDERING_CONTROL)

    private fun transport(action: String, vararg more: Pair<String, String>): Map<String, String> {
        val service = avTransport() ?: throw java.io.IOException("$label has no AVTransport service")
        return soap.call(service, action, listOf("InstanceID" to "0") + more)
    }

    override suspend fun reachable(): Boolean {
        if (device != null && !loaded) return refresh()
        if (device != null) return true
        return try {
            val answer = http.get(descriptionUrl)
            check(answer.status in HTTP_OK) { "HTTP ${answer.status}" }
            val found = UpnpDevice.parse(answer.body, descriptionUrl)
            check(found.service(UpnpDevice.AV_TRANSPORT) != null) { "no AVTransport service in the description" }
            device = found
            Diag.log(
                "cast-target",
                "$label: DLNA renderer ${found.friendlyName} (${found.model}), services " +
                    found.services.joinToString { it.type.substringAfter("service:").substringBefore(":") },
            )
            readSinks()
            readVolume()
            true
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: DLNA description $descriptionUrl could not be read", e)
            false
        } catch (e: IllegalStateException) {
            Diag.warn("cast-target", "$label: DLNA description $descriptionUrl is not a renderer", e)
            false
        } catch (e: org.xml.sax.SAXException) {
            Diag.warn("cast-target", "$label: DLNA description $descriptionUrl is not XML", e)
            false
        }
    }

    private fun refresh(): Boolean = try {
        http.get(descriptionUrl).status in HTTP_OK
    } catch (e: java.io.IOException) {
        Diag.warn("cast-target", "$label: DLNA renderer did not answer at $descriptionUrl", e)
        false
    }

    private fun readSinks() {
        val service = device?.service(UpnpDevice.CONNECTION_MANAGER) ?: return
        try {
            val sink = soap.call(service, "GetProtocolInfo")["Sink"].orEmpty()
            hlsMime = hlsMimeFrom(sink)
            Diag.log(
                "cast-target",
                "$label: accepts ${sink.split(',').size} format(s); " +
                    "HLS as ${hlsMime ?: "not listed, sending " + CastFormat.HLS_MIME}" +
                    " (sink: ${sink.take(SINK_LOGGED)})",
            )
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: GetProtocolInfo failed; sending HLS as ${CastFormat.HLS_MIME}", e)
        }
    }

    private fun readVolume() {
        val service = rendering() ?: return
        try {
            val level = soap.call(
                service,
                "GetVolume",
                listOf("InstanceID" to "0", "Channel" to "Master")
            )["CurrentVolume"]
            deviceVolume = level?.trim()?.toIntOrNull()
            Diag.log("cast-target", "$label: the renderer's volume is ${deviceVolume ?: "unreadable"}%")
        } catch (e: java.io.IOException) {
            Diag.log("cast-target", "$label: no readable volume (${e.message}); the fade will not move this device")
        }
    }

    override fun load(url: String, contentType: String, title: String, startAtMs: Long) {
        synchronized(lock) {
            generation++
            loaded = false
            sawPlaying = false
            latest = null
            misses = 0
        }
        val mime = hlsMime ?: contentType
        try {
            runCatching { transport("Stop") }
            transport(
                "SetAVTransportURI",
                "CurrentURI" to url,
                "CurrentURIMetaData" to Didl.item(title, url, Didl.protocolInfo(mime)),
            )
            transport("Play", "Speed" to "1")
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: load of \"$title\" was refused: ${e.message}")
            onEnded(true)
            return
        }
        synchronized(lock) {
            pendingSeekMs = startAtMs
            loadedAt = clock()
            loaded = true
        }
        Diag.log("cast-target", "$label: DLNA load sent as $mime from=${startAtMs}ms \"$title\"")
        startPolling()
    }

    private fun startPolling() {
        if (!polls) return
        synchronized(lock) {
            if (polling != null) return
            polling = thread(name = "dlna-poll-$label", isDaemon = true) { pollWhileWanted() }
        }
    }

    private fun pollWhileWanted() {
        var tick = 0
        var idle = 0
        while (true) {
            Thread.sleep(pollMs)
            if (!loaded) {
                synchronized(lock) {
                    if (++idle >= IDLE_TICKS && !loaded) {
                        polling = null
                        return
                    }
                }
                continue
            }
            idle = 0
            poll(readVolumeToo = tick++ % VOLUME_EVERY == 0)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    fun poll(readVolumeToo: Boolean = false) {
        val mine = generation
        if (!loaded) return
        val state: Map<String, String>
        val position: Map<String, String>
        try {
            state = transport("GetTransportInfo")
            position = transport("GetPositionInfo")
        } catch (e: java.io.IOException) {
            missed(mine, e)
            return
        }
        if (readVolumeToo) readVolume()
        var verdict: Boolean? = null
        synchronized(lock) {
            if (generation != mine || !loaded) return
            misses = 0
            val transportState = state["CurrentTransportState"].orEmpty()
            val status = state["CurrentTransportStatus"].orEmpty()
            val playing = transportState == "PLAYING"
            val at = UpnpTime.parse(position["RelTime"]) ?: 0
            val length = UpnpTime.parse(position["TrackDuration"])?.takeIf { it > 0 }
            latest = CastReading(at, length, playing, clock())
            if (playing) sawPlaying = true
            if ((transportState == "PLAYING" || transportState == "PAUSED_PLAYBACK") && pendingSeekMs > 0) {
                val wanted = pendingSeekMs
                pendingSeekMs = 0
                seekNow(wanted)
            }
            if (transportState == "STOPPED" || transportState == "NO_MEDIA_PRESENT") {
                val errored = status == "ERROR_OCCURRED"
                val waited = clock() - loadedAt
                if (sawPlaying || errored || waited > START_TIMEOUT_MS) {
                    Diag.log(
                        "cast-target",
                        "$label: renderer is $transportState (status ${status.ifEmpty { "unreported" }}) " +
                            "after ${at}ms, " +
                            "seen playing=$sawPlaying, waited ${waited}ms; the item is over, " +
                            "failed=${errored || !sawPlaying}",
                    )
                    loaded = false
                    verdict = errored || !sawPlaying
                }
            }
        }
        verdict?.let(onEnded)
    }

    private fun missed(mine: Int, e: java.io.IOException) {
        var lost = false
        synchronized(lock) {
            if (generation != mine || !loaded) return
            misses++
            Diag.warn("cast-target", "$label: poll $misses of $LOST_AFTER failed: ${e.message}")
            if (misses >= LOST_AFTER) {
                loaded = false
                lost = true
            }
        }
        if (lost) onLost("$label stopped answering")
    }

    private fun seekNow(positionMs: Long) {
        try {
            transport("Seek", "Unit" to "REL_TIME", "Target" to UpnpTime.format(positionMs))
            Diag.log("cast-target", "$label: seek to ${UpnpTime.format(positionMs)}")
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: seek to ${positionMs}ms refused: ${e.message}")
        }
    }

    override fun pause(paused: Boolean) {
        try {
            if (paused) transport("Pause") else transport("Play", "Speed" to "1")
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: ${if (paused) "pause" else "resume"} refused: ${e.message}")
        }
    }

    override fun seek(positionMs: Long) {
        latest = latest?.let { CastReading(positionMs, it.durationMs, it.playing, clock()) }
        seekNow(positionMs)
    }

    override fun volume(percent: Int) {
        val service = rendering()
        if (service == null) {
            Diag.log("cast-target", "$label: volume $percent% not sent, the renderer has no RenderingControl")
            return
        }
        try {
            soap.call(
                service,
                "SetVolume",
                listOf(
                    "InstanceID" to "0",
                    "Channel" to "Master",
                    "DesiredVolume" to percent.coerceIn(0, MAX_PERCENT).toString()
                ),
            )
            deviceVolume = percent
            Diag.log("cast-target", "$label: volume set to $percent%")
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: volume $percent% refused: ${e.message}")
        }
    }

    override fun volumePercent(): Int? = deviceVolume

    override fun stop() {
        val was = synchronized(lock) {
            generation++
            loaded.also { loaded = false }
        }
        latest = null
        if (!was) {
            Diag.log("cast-target", "$label: stop not sent, nothing is loaded from Totum")
            return
        }
        try {
            transport("Stop")
        } catch (e: java.io.IOException) {
            Diag.warn("cast-target", "$label: stop refused: ${e.message}")
        }
    }

    override fun release() {
        Diag.log("cast-target", "$label: release needs nothing for a DLNA renderer")
    }

    override fun reading(): CastReading? = latest

    companion object {
        private const val POLL_MS = 1_000L
        private const val VOLUME_EVERY = 10
        private const val IDLE_TICKS = 30
        private const val LOST_AFTER = 3
        private const val START_TIMEOUT_MS = 20_000L
        private const val SINK_LOGGED = 240
        private const val MAX_PERCENT = 100
        private val HTTP_OK = 200..299
        private val HLS_MIMES = setOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "audio/mpegurl",
            "audio/x-mpegurl",
            "application/x-hls",
        )

        fun hlsMimeFrom(sink: String): String? = sink.split(',').mapNotNull { entry ->
            entry.trim().split(':').getOrNull(2)?.trim()
        }.firstOrNull { it.lowercase() in HLS_MIMES }
    }
}
