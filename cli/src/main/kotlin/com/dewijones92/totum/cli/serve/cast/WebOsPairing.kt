package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.cli.serve.power.OkWebOsDial
import com.dewijones92.totum.cli.serve.power.Registration
import com.dewijones92.totum.cli.serve.power.WebOsDial
import com.dewijones92.totum.cli.serve.power.WebOsKey
import com.dewijones92.totum.cli.serve.power.WebOsKeys
import com.dewijones92.totum.cli.serve.power.WebOsLine
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

internal class PairingIo(
    val out: (String) -> Unit,
    val typed: () -> String?,
    val times: WebOsPairingTimes,
    val clock: () -> Long,
    val pause: suspend (Long) -> Unit,
)

@Suppress("LongParameterList")
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
    val io = PairingIo(out, typed, times, clock, pause)
    if (command.pin != null) return handThePinOver(command.pin, device.name, keyFile, pending, io)
    return PairingRun(device, WebOsKeys(keyFile), keyFile, pending, dialFor, io).run()
}

private class PairingRun(
    private val device: KnownCasts,
    private val keys: WebOsKeys,
    private val keyFile: File,
    private val pending: PendingPin,
    private val dialFor: (String) -> WebOsDial,
    private val io: PairingIo,
) {
    suspend fun run(): Int {
        if (keys.isPaired) io.out("A key for ${device.name} is already stored; pairing again replaces it.")
        io.out("Pairing ${device.name} at ${device.host} by PIN. The key will be stored in ${keyFile.path} (mode 600).")
        Diag.log("webos-pair", "${device.name}: pairing at ${device.host}, key file ${keyFile.path}")
        pending.waiting()
        return try {
            dialFor(device.host).open(null).use { line -> pairOver(line) }
        } catch (e: java.io.IOException) {
            refused(device.name, "it could not be reached or stopped answering: ${e.message}", io.out)
        } finally {
            pending.done()
        }
    }

    private suspend fun pairOver(line: WebOsLine): Int {
        val session = WebOsSession(line)
        return when (val first = session.register(null, WebOsWire.PAIRING_PIN)) {
            is Registration.Refused -> refused(device.name, first.why, io.out)
            is Registration.Registered -> stored(first.clientKey, line.certSeen)
            is Registration.NeedsPairing -> {
                Diag.log("webos-pair", "${device.name}: the TV asked for ${first.pairingType} pairing")
                val finished = finishPairing(session, first.pairingType)
                when (finished) {
                    is Registration.Registered -> stored(finished.clientKey, line.certSeen)
                    is Registration.Refused -> refused(device.name, finished.why, io.out)
                    is Registration.NeedsPairing -> refused(device.name, "the TV asked to pair again", io.out)
                }
            }
        }
    }

    private suspend fun finishPairing(session: WebOsSession, pairingType: String): Registration {
        if (pairingType != WebOsWire.PAIRING_PIN) {
            io.out("The TV is asking on its screen: press Allow with its remote.")
            return session.awaitRegistered()
        }
        io.out(
            "The TV is showing a PIN. Give it to me with:  totum webos-pair \"${device.name}\" " +
                "--pin=<the PIN>  (or type it here and press Enter). Waiting up to " +
                "${io.times.waitForPinMs / MS_PER_S}s.",
        )
        val pin = waitForPin(pending, io)
            ?: return Registration.Refused("no PIN arrived in time")
        Diag.log("webos-pair", "${device.name}: a PIN of ${pin.length} digits arrived")
        return session.submitPin(pin)
    }

    private suspend fun stored(clientKey: String?, cert: String?): Int {
        if (clientKey.isNullOrEmpty()) return refused(device.name, "the TV registered us but sent no key", io.out)
        keys.write(WebOsKey(clientKey, cert))
        Diag.log("webos-pair", "${device.name}: paired; key stored, certificate pinned=${cert != null}")
        io.out("✅ Paired ${device.name}. The key is stored (mode 600) and never printed or logged.")
        val inputs = WebOsTv(device.name, dialFor(device.host), keys).inputs()
        if (inputs.isEmpty()) {
            io.out("(Could not read the TV's inputs just now.)")
        } else {
            io.out("Inputs on the TV, for TOTUM_SHOWN_ON (name=${device.name}@<id>):")
            inputs.forEach { io.out("  ${it.id}  \"${it.label}\"${if (it.connected) "  connected" else ""}") }
        }
        return 0
    }
}

private fun refused(name: String, why: String, out: (String) -> Unit): Int {
    out("❌ $name was not paired: $why")
    Diag.warn("webos-pair", "$name: not paired: $why")
    return 1
}

private suspend fun waitForPin(pending: PendingPin, io: PairingIo): String? {
    val began = io.clock()
    while (io.clock() - began < io.times.waitForPinMs) {
        (pending.handed() ?: io.typed()?.trim())?.takeIf { it.isNotEmpty() }?.let { return it }
        io.pause(io.times.pollMs)
    }
    return null
}

private suspend fun handThePinOver(pin: String, name: String, keyFile: File, pending: PendingPin, io: PairingIo): Int {
    val out = io.out
    if (!PIN_SHAPE.matches(pin)) return refused(name, "a PIN is 4 to 12 digits", out)
    if (!pending.isWaiting()) {
        out(
            "No pairing of $name is waiting for a PIN. Start one first:  totum webos-pair \"$name\"  " +
                "(the TV then shows a PIN), then run this with --pin=<the PIN>.",
        )
        Diag.log("webos-pair", "$name: a PIN was given but no pairing is waiting")
        return 1
    }
    val began = io.clock()
    pending.hand(pin)
    Diag.log("webos-pair", "$name: a PIN of ${pin.length} digits was handed to the waiting pairing")
    out(
        "The PIN was handed to the pairing that is waiting. " +
            "Waiting up to ${io.times.waitForResultMs / MS_PER_S}s for it.",
    )
    while (io.clock() - began < io.times.waitForResultMs) {
        if (keyFile.isFile && keyFile.lastModified() >= began - MTIME_SLACK_MS) {
            out("✅ $name is paired.")
            return 0
        }
        io.pause(io.times.pollMs)
    }
    return refused(name, "the waiting pairing did not store a key in time (see its output and the Pi's log)", out)
}

private fun lineTypedNow(): String? = if (System.`in`.available() > 0) readlnOrNull() else null

private val PIN_SHAPE = Regex("""\d{4,12}""")
private const val MS_PER_S = 1_000L
private const val MTIME_SLACK_MS = 2_000L
