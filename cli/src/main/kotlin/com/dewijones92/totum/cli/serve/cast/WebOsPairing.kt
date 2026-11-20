package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.cli.serve.power.OkWebOsDial
import com.dewijones92.totum.cli.serve.power.Registration
import com.dewijones92.totum.cli.serve.power.WebOsDial
import com.dewijones92.totum.cli.serve.power.WebOsKey
import com.dewijones92.totum.cli.serve.power.WebOsKeys
import com.dewijones92.totum.cli.serve.power.WebOsSession
import com.dewijones92.totum.cli.serve.power.WebOsTv
import com.dewijones92.totum.cli.serve.power.WebOsWire
import com.dewijones92.totum.cli.serve.power.writeOwnerOnly
import com.dewijones92.totum.common.Diag
import java.io.File
import java.nio.file.Files

internal class PendingPin(private val dir: File, id: String, private val clock: () -> Long) {
    private val marker = File(dir, "pending-$id.wait")
    private val pinFile = File(dir, "pending-$id.pin")

    fun waiting() {
        dir.mkdirs()
        Files.deleteIfExists(pinFile.toPath())
        marker.writeText(clock().toString())
    }

    fun isWaiting(): Boolean {
        val since = runCatching { marker.readText().trim().toLong() }.getOrNull() ?: return false
        return clock() - since <= FRESH_MS
    }

    fun hand(pin: String) = writeOwnerOnly(pinFile, pin)

    fun handed(): String? = pinFile.takeIf { it.isFile }?.let { runCatching { it.readText().trim() }.getOrNull() }
        ?.takeIf { it.isNotEmpty() }

    fun done() {
        Files.deleteIfExists(pinFile.toPath())
        Files.deleteIfExists(marker.toPath())
    }

    companion object {
        const val FRESH_MS = 190_000L
    }
}

internal class WebOsPairingTimes(
    val waitForPinMs: Long = 180_000L,
    val waitForResultMs: Long = 30_000L,
    val pollMs: Long = 500L,
)

@Suppress("LongParameterList", "ReturnCount", "LongMethod", "CyclomaticComplexMethod")
internal suspend fun webOsPair(
    command: Command.WebOsPair,
    out: (String) -> Unit,
    env: (String) -> String? = System::getenv,
    dialFor: (String) -> WebOsDial = { host -> OkWebOsDial(listOf("wss://$host:3001", "ws://$host:3000")) },
    typed: () -> String? = ::lineTypedNow,
    times: WebOsPairingTimes = WebOsPairingTimes(),
    clock: () -> Long = System::currentTimeMillis,
    pause: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): Int {
    val configured = knownCastsFrom(env("TOTUM_WEBOS_TARGETS"))
    val device = configured.firstOrNull { castIdOf(it.name) == castIdOf(command.device) }
    if (device == null) {
        out(
            "'${command.device}' is not in TOTUM_WEBOS_TARGETS " +
                "(${configured.joinToString { it.name }.ifEmpty { "empty" }}). " +
                "Add  ${command.device}=<its address>  there first: the key is stored under the TV's name.",
        )
        Diag.log("webos-pair", "refused: ${command.device} is not configured")
        return 1
    }
    val id = castIdOf(device.name)
    val dir = File(File(env("TOTUM_DATA") ?: "${System.getProperty("user.home")}/.totum"), "webos")
    val keyFile = File(dir, "webos-$id.json")
    val pending = PendingPin(dir, id, clock)
    if (command.pin != null) return handThePinOver(command.pin, device.name, keyFile, pending, out, times, clock, pause)

    val keys = WebOsKeys(keyFile)
    if (keys.isPaired) out("A key for ${device.name} is already stored; pairing again replaces it.")
    out("Pairing ${device.name} at ${device.host} by PIN. The key will be stored in ${keyFile.path} (mode 600).")
    Diag.log("webos-pair", "${device.name}: pairing at ${device.host}, key file ${keyFile.path}")
    pending.waiting()
    try {
        dialFor(device.host).open(null).use { line ->
            val session = WebOsSession(line)
            when (val first = session.register(null, WebOsWire.PAIRING_PIN)) {
                is Registration.Refused -> return refused(device.name, first.why, out)
                is Registration.Registered -> return stored(device, first.clientKey, line.certSeen, keys, dialFor, out)
                is Registration.NeedsPairing -> {
                    Diag.log("webos-pair", "${device.name}: the TV asked for ${first.pairingType} pairing")
                    val finished = if (first.pairingType == WebOsWire.PAIRING_PIN) {
                        out(
                            "The TV is showing a PIN. Give it to me with:  totum webos-pair \"${device.name}\" " +
                                "--pin=<the PIN>  (or type it here and press Enter). Waiting up to " +
                                "${times.waitForPinMs / MS_PER_S}s.",
                        )
                        val pin = waitForPin(pending, typed, times, clock, pause)
                            ?: return refused(device.name, "no PIN arrived in time", out)
                        Diag.log("webos-pair", "${device.name}: a PIN of ${pin.length} digits arrived")
                        session.submitPin(pin)
                    } else {
                        out("The TV is asking on its screen: press Allow with its remote.")
                        session.awaitRegistered()
                    }
                    return when (finished) {
                        is Registration.Registered -> stored(device, finished.clientKey, line.certSeen, keys, dialFor, out)
                        is Registration.Refused -> refused(device.name, finished.why, out)
                        is Registration.NeedsPairing -> refused(device.name, "the TV asked to pair again", out)
                    }
                }
            }
        }
    } catch (e: java.io.IOException) {
        return refused(device.name, "it could not be reached or stopped answering: ${e.message}", out)
    } finally {
        pending.done()
    }
}

private suspend fun stored(
    device: KnownCasts,
    clientKey: String?,
    cert: String?,
    keys: WebOsKeys,
    dialFor: (String) -> WebOsDial,
    out: (String) -> Unit,
): Int {
    if (clientKey.isNullOrEmpty()) return refused(device.name, "the TV registered us but sent no key", out)
    keys.write(WebOsKey(clientKey, cert))
    Diag.log("webos-pair", "${device.name}: paired; key stored, certificate pinned=${cert != null}")
    out("✅ Paired ${device.name}. The key is stored (mode 600) and never printed or logged.")
    val tv = WebOsTv(device.name, dialFor(device.host), keys)
    val inputs = tv.inputs()
    if (inputs.isEmpty()) {
        out("(Could not read the TV's inputs just now.)")
    } else {
        out("Inputs on the TV, for TOTUM_SHOWN_ON (name=${device.name}@<id>):")
        inputs.forEach { out("  ${it.id}  \"${it.label}\"${if (it.connected) "  connected" else ""}") }
    }
    return 0
}

private fun refused(name: String, why: String, out: (String) -> Unit): Int {
    out("❌ $name was not paired: $why")
    Diag.warn("webos-pair", "$name: not paired: $why")
    return 1
}

private suspend fun waitForPin(
    pending: PendingPin,
    typed: () -> String?,
    times: WebOsPairingTimes,
    clock: () -> Long,
    pause: suspend (Long) -> Unit,
): String? {
    val began = clock()
    while (clock() - began < times.waitForPinMs) {
        (pending.handed() ?: typed()?.trim())?.takeIf { it.isNotEmpty() }?.let { return it }
        pause(times.pollMs)
    }
    return null
}

@Suppress("LongParameterList")
private suspend fun handThePinOver(
    pin: String,
    name: String,
    keyFile: File,
    pending: PendingPin,
    out: (String) -> Unit,
    times: WebOsPairingTimes,
    clock: () -> Long,
    pause: suspend (Long) -> Unit,
): Int {
    if (!PIN_SHAPE.matches(pin)) return refused(name, "a PIN is 4 to 12 digits", out)
    if (!pending.isWaiting()) {
        out(
            "No pairing of $name is waiting for a PIN. Start one first:  totum webos-pair \"$name\"  " +
                "(the TV then shows a PIN), then run this with --pin=<the PIN>.",
        )
        Diag.log("webos-pair", "$name: a PIN was given but no pairing is waiting")
        return 1
    }
    val began = clock()
    pending.hand(pin)
    Diag.log("webos-pair", "$name: a PIN of ${pin.length} digits was handed to the waiting pairing")
    out("The PIN was handed to the pairing that is waiting. Waiting up to ${times.waitForResultMs / MS_PER_S}s for it.")
    while (clock() - began < times.waitForResultMs) {
        if (keyFile.isFile && keyFile.lastModified() >= began - MTIME_SLACK_MS) {
            out("✅ $name is paired.")
            return 0
        }
        pause(times.pollMs)
    }
    return refused(name, "the waiting pairing did not store a key in time (see its output and the Pi's log)", out)
}

private fun lineTypedNow(): String? = if (System.`in`.available() > 0) readlnOrNull() else null

private val PIN_SHAPE = Regex("""\d{4,12}""")
private const val MS_PER_S = 1_000L
private const val MTIME_SLACK_MS = 2_000L
