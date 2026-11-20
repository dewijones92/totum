package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.delay

internal class WolStep(
    private val label: String,
    private val mac: ByteArray,
    private val broadcasts: List<String>,
    private val host: String?,
    private val sender: WakeSender,
) : WakeStep {
    override val name: String
        get() = "wake-on-LAN to " + (broadcasts + listOfNotNull(host?.let { "$it (direct)" })).joinToString()

    override val repeatsEveryMs: Long = RESEND_MS

    override suspend fun run(): Boolean {
        val packet = WakeOnLan.magicPacket(mac)
        val tried = broadcasts.flatMap { address -> WakeOnLan.PORTS.map { port -> address to port } } +
            listOfNotNull(host?.let { it to WakeOnLan.UNICAST_PORT })
        val sent = mutableListOf<String>()
        val failed = mutableListOf<String>()
        tried.forEach { (address, port) ->
            runCatching { sender.send(packet, address, port) }
                .onSuccess { sent += "$address:$port" }
                .onFailure {
                    failed += "$address:$port"
                    Diag.warn("cast-target", "$label: wake packet to $address:$port not sent", it)
                }
        }
        Diag.log(
            "cast-target",
            "$label: wake packets sent=${sent.size} of ${tried.size} to ${sent.joinToString().ifEmpty { "none" }}" +
                if (failed.isEmpty()) "" else "; not sent to ${failed.joinToString()}",
        )
        return sent.isNotEmpty()
    }

    companion object {
        const val RESEND_MS = 5_000L
    }
}

@Suppress("LongParameterList")
internal fun wakeOnLanPower(
    label: String,
    mac: ByteArray,
    broadcasts: List<String>,
    host: String? = null,
    sender: WakeSender,
    answers: suspend () -> Boolean,
    off: PowerOff? = null,
    readySteps: List<WakeStep> = emptyList(),
    pause: suspend (Long) -> Unit = { delay(it) },
    clock: () -> Long = System::currentTimeMillis,
): SteppedPower = SteppedPower(
    label = label,
    wakeSteps = listOf(WolStep(label, mac, broadcasts, host, sender)),
    answers = answers,
    wakeFailed = "❌ $label didn't wake: check 'Turn on via Wi-Fi' in its settings",
    readySteps = readySteps,
    off = off,
    pause = pause,
    clock = clock,
)
