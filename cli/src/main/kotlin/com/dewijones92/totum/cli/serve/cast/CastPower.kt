package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.Adb
import com.dewijones92.totum.cli.serve.power.AdbStartApp
import com.dewijones92.totum.cli.serve.power.AdbWakeStick
import com.dewijones92.totum.cli.serve.power.CecNudge
import com.dewijones92.totum.cli.serve.power.CecWakeStep
import com.dewijones92.totum.cli.serve.power.DevicePower
import com.dewijones92.totum.cli.serve.power.OkWebOsDial
import com.dewijones92.totum.cli.serve.power.PowerOff
import com.dewijones92.totum.cli.serve.power.ShownOnStep
import com.dewijones92.totum.cli.serve.power.ShownTv
import com.dewijones92.totum.cli.serve.power.SteppedPower
import com.dewijones92.totum.cli.serve.power.SwitchOffDisplay
import com.dewijones92.totum.cli.serve.power.UdpWakeSender
import com.dewijones92.totum.cli.serve.power.WakeOnLan
import com.dewijones92.totum.cli.serve.power.WakeSender
import com.dewijones92.totum.cli.serve.power.WakeStep
import com.dewijones92.totum.cli.serve.power.WebOsDial
import com.dewijones92.totum.cli.serve.power.WebOsKeys
import com.dewijones92.totum.cli.serve.power.WebOsPowerOff
import com.dewijones92.totum.cli.serve.power.WebOsTv
import com.dewijones92.totum.cli.serve.power.WolStep
import com.dewijones92.totum.cli.serve.speakers.Shell
import com.dewijones92.totum.cli.serve.speakers.SystemShell
import com.dewijones92.totum.common.Diag
import java.io.File

internal class AdbTarget(val address: String, val where: String)

internal class ShownOn(val display: String, val input: String?)

internal fun adbTargetsFrom(spec: String?): Map<String, AdbTarget> = spec.orEmpty().split(';').mapNotNull { pair ->
    val name = pair.substringBefore('=').trim()
    val rest = pair.substringAfter('=', "").trim()
    val address = rest.substringBefore('|').trim()
    if (name.isEmpty() || address.isEmpty()) return@mapNotNull null
    val where = rest.substringAfter('|', "").trim().ifEmpty { "its Android device" }
    castIdOf(name) to AdbTarget(address, where)
}.toMap()

internal fun startAppsFrom(spec: String?): Map<String, String> = spec.orEmpty().split(';').mapNotNull { pair ->
    val name = pair.substringBefore('=').trim()
    val component = pair.substringAfter('=', "").trim()
    if (name.isEmpty() || !component.contains('/')) null else castIdOf(name) to component
}.toMap()

internal fun shownOnFrom(spec: String?): Map<String, ShownOn> = spec.orEmpty().split(';').mapNotNull { pair ->
    val name = pair.substringBefore('=').trim()
    val rest = pair.substringAfter('=', "").trim()
    val display = rest.substringBefore('@').trim()
    if (name.isEmpty() || display.isEmpty()) return@mapNotNull null
    castIdOf(name) to ShownOn(display, rest.substringAfter('@', "").trim().ifEmpty { null })
}.toMap()

internal class PowerTiming(
    val cecAfterWolMs: Long = CEC_AFTER_WOL_MS,
    val cecSettleMs: Long = CecNudge.SETTLE_MS,
    val cecMinGapMs: Long = CecNudge.MIN_GAP_MS,
    val cecWakeTimeoutMs: Long = CEC_WAKE_TIMEOUT_MS,
) {
    companion object {
        const val CEC_AFTER_WOL_MS = 5_000L
        const val CEC_WAKE_TIMEOUT_MS = 45_000L
    }
}

internal class PowerConfig(
    val webos: Map<String, KnownCasts> = emptyMap(),
    val adb: Map<String, AdbTarget> = emptyMap(),
    val startApps: Map<String, String> = emptyMap(),
    val shownOn: Map<String, ShownOn> = emptyMap(),
    val webosDir: File? = null,
    val adbBinary: String = "adb",
    val shell: Shell = SystemShell(),
    val dialFor: (host: String) -> WebOsDial = { host -> OkWebOsDial(listOf("wss://$host:3001", "ws://$host:3000")) },
    val sender: WakeSender = UdpWakeSender(),
    val timing: PowerTiming = PowerTiming(),
) {
    fun webosKeyFile(id: String): File? = webosDir?.let { File(it, "webos-$id.json") }

    companion object {
        fun fromEnv(env: (String) -> String?, dataDir: File): PowerConfig = PowerConfig(
            webos = knownCastsFrom(env("TOTUM_WEBOS_TARGETS")).associateBy { castIdOf(it.name) },
            adb = adbTargetsFrom(env("TOTUM_ADB_TARGETS")),
            startApps = startAppsFrom(env("TOTUM_START_APPS")),
            shownOn = shownOnFrom(env("TOTUM_SHOWN_ON")),
            webosDir = File(dataDir, "webos"),
            adbBinary = env("TOTUM_ADB")?.takeIf { it.isNotBlank() } ?: "adb",
        )
    }
}

internal class DevicePowers(
    private val devices: List<KnownDevice>,
    private val config: PowerConfig,
    private val extraBroadcasts: List<String>,
) {
    private val byId = devices.associateBy { it.id }
    private val powers = HashMap<String, DevicePower?>()
    private val tvs = HashMap<String, WebOsTv?>()
    private val building = HashSet<String>()
    private val nudges = HashMap<String, CecNudge>()

    init {
        (config.webos.keys + config.adb.keys + config.startApps.keys + config.shownOn.keys)
            .filter { it !in byId }
            .forEach { Diag.warn("cast-target", "a power setting names \"$it\", which is not a configured device") }
    }

    @Synchronized
    fun powerFor(device: KnownDevice): DevicePower? {
        if (powers.containsKey(device.id)) return powers[device.id]
        if (!building.add(device.id)) {
            Diag.warn("cast-target", "${device.name}: its display setting leads back to itself, so it is ignored")
            return null
        }
        try {
            return build(device).also {
                powers[device.id] = it
                Diag.log("cast-target", "${device.name}: power ${it?.let(::describe) ?: "is not managed by Totum"}")
            }
        } finally {
            building.remove(device.id)
        }
    }

    @Synchronized
    fun webosFor(device: KnownDevice): WebOsTv? {
        if (tvs.containsKey(device.id)) return tvs[device.id]
        val host = config.webos[device.id]?.host
        val tv = host?.let { WebOsTv(device.name, config.dialFor(it), WebOsKeys(config.webosKeyFile(device.id))) }
        tvs[device.id] = tv
        return tv
    }

    private fun describe(power: DevicePower): String =
        (if (power.startsApp) "starts its app" else "wakes it") + (if (power.canSwitchOff) ", can switch off" else "")

    @Suppress("CyclomaticComplexMethod")
    private fun build(device: KnownDevice): DevicePower? {
        val webos = webosFor(device)
        val shown = config.shownOn[device.id]
        val displayDevice = shown?.let { byId[castIdOf(it.display)] }
        if (shown != null && displayDevice == null) {
            Diag.warn(
                "cast-target",
                "${device.name} is shown on \"${shown.display}\", which is not a configured device"
            )
        }
        val display = displayDevice?.let(::powerFor)
        val ports = device.order.mapNotNull { protocol ->
            protocol.port ?: portOf(device.addresses.getValue(protocol).host)
        }.map { ReceiverProbe(device.host, it) }
        val probe = TvPowerProbe(webos, ports)
        val app = config.startApps[device.id]
        val adb = config.adb[device.id]?.let { Adb(it.address, config.shell, config.adbBinary) }
        val startsApp = adb != null && app != null
        val hasWol = device.mac?.let(WakeOnLan::parseMac) != null
        val cec = cecStepsFor(device, hasWol)
        val wake = buildList<WakeStep> {
            device.mac?.let(WakeOnLan::parseMac)?.let { mac ->
                add(
                    WolStep(
                        device.name,
                        mac,
                        WakeOnLan.broadcastsFor(device.host, extraBroadcasts),
                        device.host,
                        config.sender,
                    ),
                )
            }
            if (adb != null && app != null) {
                val onDisplay = if (shown != null && display != null) ShownTv(shown.display, display) else null
                add(AdbWakeStick(device.name, adb, nudgeFor(device.id), onDisplay))
                add(AdbStartApp(device.name, adb, app, config.adb.getValue(device.id).where))
            }
            addAll(cec)
        }
        val ready = if (shown != null && displayDevice != null && display != null) {
            listOf(ShownOnStep(display, shown.display, webosFor(displayDevice), shown.input))
        } else {
            emptyList()
        }
        val off: PowerOff? = webos?.let(::WebOsPowerOff)
            ?: if (shown != null && display != null) SwitchOffDisplay(display, shown.display) else null
        if (wake.isEmpty() && ready.isEmpty() && off == null) return null
        return SteppedPower(
            label = device.name,
            wakeSteps = wake,
            answers = probe::answers,
            wakeFailed = if (startsApp) {
                "❌ ${device.name} didn't start"
            } else {
                "❌ ${device.name} didn't wake: check 'Turn on via Wi-Fi' in its settings"
            },
            readySteps = ready,
            off = off,
            startsApp = startsApp,
            evidence = { probe.evidence },
            timeoutMs = when {
                startsApp -> AdbStartApp.START_TIMEOUT_MS
                cec.isNotEmpty() -> config.timing.cecWakeTimeoutMs
                else -> SteppedPower.WAKE_TIMEOUT_MS
            },
        ).also { it.watch() }
    }

    private fun nudgeFor(id: String): CecNudge? {
        val stick = config.adb[id] ?: return null
        return nudges.getOrPut(id) {
            CecNudge(
                byId[id]?.name ?: id,
                Adb(stick.address, config.shell, config.adbBinary),
                config.timing.cecSettleMs,
                config.timing.cecMinGapMs,
            )
        }
    }

    private fun cecStepsFor(device: KnownDevice, hasWol: Boolean): List<WakeStep> =
        config.shownOn.entries
            .filter { (stickId, shown) -> castIdOf(shown.display) == device.id && stickId in config.adb }
            .mapNotNull { (stickId, _) ->
                val stick = byId[stickId] ?: return@mapNotNull null
                val nudge = nudgeFor(stickId) ?: return@mapNotNull null
                CecWakeStep(device.name, stick.name, nudge, if (hasWol) config.timing.cecAfterWolMs else 0L)
            }
}
