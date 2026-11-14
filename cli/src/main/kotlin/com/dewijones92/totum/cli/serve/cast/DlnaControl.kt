package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.cast.CastFormat

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

    override val keepsAddressesPrivate: Boolean get() = true

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

    override val model: String? get() = device?.model ?: device?.friendlyName

    override val showsPictures: Boolean get() = picturesListed

    private val soap = UpnpSoap(http)

    @Volatile private var device: UpnpDevice? = null

    @Volatile private var hlsMime: String? = null

    @Volatile private var picturesListed = true

    @Volatile private var deviceVolume: Int? = null

    private val watch = RendererWatch(
        label,
        clock,
        pollMs,
        polls,
        look = ::look,
        seekNow = ::seekNow,
        extras = ::readVolume,
    )

    private fun avTransport() = device?.service(UpnpDevice.AV_TRANSPORT)

    private fun rendering() = device?.service(UpnpDevice.RENDERING_CONTROL)

    private fun transport(action: String, vararg more: Pair<String, String>): Map<String, String> {
        val service = avTransport() ?: throw java.io.IOException("$label has no AVTransport service")
        return soap.call(service, action, listOf("InstanceID" to "0") + more)
    }

    override suspend fun reachable(): Boolean {
        if (device != null && !watch.isLoaded) return refresh()
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
            picturesListed = takesPictures(sink)
            val pictures = if (picturesListed) {
                "video or HLS listed, so it is sent the picture"
            } else {
                "no video format listed, so it gets sound only"
            }
            Diag.log(
                "cast-target",
                "$label: accepts ${sink.split(',').size} format(s); " +
                    "HLS as ${hlsMime ?: "not listed, sending " + CastFormat.HLS_MIME}; $pictures " +
                    "(sink: ${sink.take(SINK_LOGGED)})",
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
        watch.beforeLoad()
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
            watch.refused()
            return
        }
        watch.loaded(startAtMs)
        Diag.log("cast-target", "$label: DLNA load sent as $mime from=${startAtMs}ms \"$title\"")
    }

    fun poll(readVolumeToo: Boolean = false) = watch.poll(readVolumeToo)

    private fun look(): RendererSnapshot {
        val state = transport("GetTransportInfo")
        val position = transport("GetPositionInfo")
        val transportState = state["CurrentTransportState"].orEmpty()
        val status = state["CurrentTransportStatus"].orEmpty()
        return RendererSnapshot(
            state = when (transportState) {
                "PLAYING" -> RendererState.PLAYING
                "PAUSED_PLAYBACK" -> RendererState.PAUSED
                "STOPPED", "NO_MEDIA_PRESENT" -> RendererState.OVER
                else -> RendererState.OTHER
            },
            positionMs = UpnpTime.parse(position["RelTime"]) ?: 0,
            durationMs = UpnpTime.parse(position["TrackDuration"])?.takeIf { it > 0 },
            errored = status == "ERROR_OCCURRED",
            detail = "$transportState (status ${status.ifEmpty { "unreported" }})",
        )
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
        watch.moved(positionMs)
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
        if (!watch.stopped()) {
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

    override fun reading(): CastReading? = watch.reading

    companion object {
        private const val POLL_MS = 1_000L
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

        private fun mimesOf(sink: String): List<String> = sink.split(',').mapNotNull { entry ->
            entry.trim().split(':').getOrNull(2)?.trim()?.lowercase()
        }

        fun hlsMimeFrom(sink: String): String? = sink.split(',').mapNotNull { entry ->
            entry.trim().split(':').getOrNull(2)?.trim()
        }.firstOrNull { it.lowercase() in HLS_MIMES }

        fun takesPictures(sink: String): Boolean {
            val mimes = mimesOf(sink)
            return mimes.isEmpty() || mimes.any { it in HLS_MIMES || it.startsWith("video/") }
        }
    }
}
