package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.targets.FoundTarget
import com.dewijones92.totum.cli.serve.targets.TargetKit
import com.dewijones92.totum.cli.serve.targets.TargetSource
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.domain.CastScreen
import com.dewijones92.totum.playback.cast.CastFormat
import java.io.File
import java.net.URI

private const val CHROMECAST_PORT = 8009
private const val AIRPLAY_PORT = 7000

internal enum class CastProtocol(val word: String, val port: Int?) {
    CHROMECAST("chromecast", CHROMECAST_PORT),
    AIRPLAY("airplay", AIRPLAY_PORT),
    DLNA("dlna", null),
    ;

    companion object {
        val DEFAULT_ORDER = listOf(CHROMECAST, AIRPLAY, DLNA)

        fun named(word: String): CastProtocol? = entries.firstOrNull { it.word == word.trim().lowercase() }
    }
}

internal fun castIdOf(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

internal class KnownDevice(
    val name: String,
    val id: String,
    val addresses: Map<CastProtocol, KnownCasts>,
    val first: CastProtocol?,
) {
    val order: List<CastProtocol>
        get() = (listOfNotNull(first) + CastProtocol.DEFAULT_ORDER).distinct().filter { it in addresses }

    val mac: String? get() = addresses.values.firstNotNullOfOrNull { it.mac }

    val host: String
        get() = addresses.values.first().let { entry ->
            runCatching { URI(entry.host).host }.getOrNull() ?: entry.host
        }

    val kind: TargetKind
        get() = if (name.contains("chromecast", ignoreCase = true)) TargetKind.CHROMECAST else TargetKind.TV

    fun describe(): String = order.joinToString("+") { it.word } +
        (mac?.let { " (wake $it)" } ?: "")
}

internal fun knownDevicesFrom(
    chromecast: List<KnownCasts>,
    airplay: List<KnownCasts>,
    dlna: List<KnownCasts>,
    firstSpec: String? = null,
): List<KnownDevice> {
    val first = firstSpec.orEmpty().split(';').mapNotNull { pair ->
        val parts = pair.split('=', limit = 2)
        val protocol = parts.getOrNull(1)?.let(CastProtocol::named)
        if (parts.first().isBlank() || protocol == null) null else castIdOf(parts.first()) to protocol
    }.toMap()
    val byId = linkedMapOf<String, Pair<String, MutableMap<CastProtocol, KnownCasts>>>()
    listOf(CastProtocol.CHROMECAST to chromecast, CastProtocol.AIRPLAY to airplay, CastProtocol.DLNA to dlna)
        .forEach { (protocol, entries) ->
            entries.forEach { entry ->
                val id = castIdOf(entry.name)
                byId.getOrPut(id) { entry.name to linkedMapOf() }.second[protocol] = entry
            }
        }
    return byId.map { (id, named) -> KnownDevice(named.first, id, named.second, first[id]) }
}

internal class FirstReachableControl(
    private val label: String,
    private val controls: List<Pair<CastProtocol, CastControl>>,
) : CastControl {

    @Volatile private var active = controls.first()

    private val current: CastControl get() = active.second

    private var endedHandler: (failed: Boolean) -> Unit = {}
    private var lostHandler: (reason: String) -> Unit = {}

    init {
        controls.forEach { (_, control) ->
            control.onEnded = { failed -> if (control === current) endedHandler(failed) }
            control.onLost = { reason -> if (control === current) lostHandler(reason) }
        }
    }

    override var onEnded: (failed: Boolean) -> Unit
        get() = endedHandler
        set(value) {
            endedHandler = value
        }

    override var onLost: (reason: String) -> Unit
        get() = lostHandler
        set(value) {
            lostHandler = value
        }

    override val format: CastFormat get() = current.format
    override val model: String? get() = current.model
    override val screen: CastScreen? get() = current.screen

    override suspend fun reachable(): Boolean {
        val tried = mutableListOf<String>()
        for (candidate in listOf(active) + controls.filter { it !== active }) {
            if (candidate.second.reachable()) {
                if (candidate !== active) {
                    Diag.log(
                        "cast-target",
                        "$label: now driven over ${candidate.first.word}, not ${active.first.word} " +
                            "(unreachable: ${tried.joinToString().ifEmpty { "none" }})",
                    )
                    current.stop()
                    active = candidate
                }
                Diag.log("cast-target", "$label: driven over ${active.first.word} (tried ${tried.size} before it)")
                return true
            }
            tried += candidate.first.word
        }
        Diag.warn("cast-target", "$label: no protocol answered (tried ${tried.joinToString()})")
        return false
    }

    override fun load(url: String, contentType: String, title: String, startAtMs: Long) =
        current.load(url, contentType, title, startAtMs)

    override fun pause(paused: Boolean) = current.pause(paused)

    override fun seek(positionMs: Long) = current.seek(positionMs)

    override fun volume(percent: Int) = current.volume(percent)

    override fun volumePercent(): Int? = current.volumePercent()

    override fun stop() = current.stop()

    override fun release() = current.release()

    override fun reading(): CastReading? = current.reading()
}

internal class CastPlaces(
    val handbackDir: File?,
    val airplayDir: File?,
    val airplayHelper: String = System.getenv("TOTUM_AIRPLAY_HELPER") ?: "/opt/totum/airplay_helper.py",
) {
    fun credentialsOf(id: String): File? = airplayDir?.let { File(it, "airplay-$id.json") }
}

internal class CastDeviceSource(
    private val devices: List<KnownDevice>,
    private val streams: PiStreams,
    private val extraBroadcasts: List<String> = emptyList(),
    private val places: CastPlaces = CastPlaces(null, null),
) : TargetSource {

    override suspend fun find(fresh: Boolean): List<FoundTarget> = devices.map { device ->
        FoundTarget(Target(device.id, device.name, device.kind)) {
            val controls = device.order.map { protocol -> protocol to controlFor(device, protocol) }
            val control = controls.singleOrNull()?.second ?: FirstReachableControl(device.name, controls)
            val wake = wakeFor(device)
            TargetKit(ChromecastPlayer(device.name, control, streams, wake), CastLink(device.host, control, wake))
        }
    }.also {
        Diag.log(
            "cast-target",
            "configured ${it.size} cast target(s): " + devices.joinToString { device ->
                "${device.name}=${device.describe()} over ${device.order.first().word} first"
            },
        )
    }

    private fun controlFor(device: KnownDevice, protocol: CastProtocol): CastControl {
        val address = device.addresses.getValue(protocol).host
        return when (protocol) {
            CastProtocol.CHROMECAST -> PyCastControl(
                address,
                device.name,
                stateFile = places.handbackDir?.let { File(it.apply { mkdirs() }, "handback-${device.id}.json") },
            )
            CastProtocol.AIRPLAY -> PyCastControl(
                address,
                device.name,
                helper = places.airplayHelper,
                stateFile = places.credentialsOf(device.id)?.also { it.parentFile?.mkdirs() },
                format = CastFormat.HLS,
                kind = "AirPlay",
            )
            CastProtocol.DLNA -> DlnaControl(address, device.name)
        }
    }

    private fun wakeFor(device: KnownDevice): TvWake? {
        val mac = device.mac?.let(WakeOnLan::parseMac) ?: return null
        val broadcasts = WakeOnLan.broadcastsFor(device.host, extraBroadcasts)
        val ports = device.order.mapNotNull { protocol ->
            protocol.port ?: runCatching { URI(device.addresses.getValue(protocol).host).port }.getOrNull()
                ?.takeIf { it > 0 }
        }
        val probes = ports.map { ReceiverProbe(device.host, it) }
        return TvWake(
            label = device.name,
            mac = mac,
            broadcasts = broadcasts,
            host = device.host,
            sender = UdpWakeSender(),
            answers = { probes.any { it.answers() } },
        ).also { it.watch() }
    }
}
