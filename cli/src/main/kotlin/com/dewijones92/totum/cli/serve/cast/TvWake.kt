package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetPower
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class ReceiverProbe(
    private val host: String,
    private val port: Int = CAST_PORT,
    private val timeoutMs: Int = CONNECT_MS,
) {
    suspend fun answers(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) } }.isSuccess
    }

    private companion object {
        const val CAST_PORT = 8009
        const val CONNECT_MS = 1_500
    }
}

internal class TvWake(
    private val label: String,
    private val mac: ByteArray,
    private val broadcasts: List<String>,
    private val sender: WakeSender,
    private val answers: suspend () -> Boolean,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var power: TargetPower = TargetPower.ON
        private set

    @Volatile var failure: String? = null
        private set

    @Volatile var sentWake: Boolean = false
        private set

    private val lock = Mutex()
    private val watching = AtomicBoolean(false)

    suspend fun refresh() {
        if (!lock.tryLock()) return
        try {
            val next = if (answers()) TargetPower.ON else TargetPower.ASLEEP
            if (next != power) Diag.log("cast-target", "$label: ${power.name} -> ${next.name} (receiver port probe)")
            power = next
        } finally {
            lock.unlock()
        }
    }

    suspend fun ensureAwake(): Boolean = lock.withLock {
        sentWake = false
        if (answers()) {
            power = TargetPower.ON
            failure = null
            return@withLock true
        }
        power = TargetPower.WAKING
        failure = null
        val startedAt = clock()
        var sentAt = startedAt
        Diag.log("cast-target", "$label: not answering, waking it (wake-on-LAN to ${broadcasts.joinToString()})")
        sendPackets()
        while (clock() - startedAt < WAKE_TIMEOUT_MS) {
            pause(POLL_MS)
            if (answers()) {
                power = TargetPower.ON
                Diag.log("cast-target", "$label: answered ${clock() - startedAt}ms after the wake")
                return@withLock true
            }
            if (clock() - sentAt >= RESEND_MS) {
                sentAt = clock()
                Diag.log("cast-target", "$label: silent ${sentAt - startedAt}ms after the wake, sending it again")
                sendPackets()
            }
        }
        power = TargetPower.ASLEEP
        failure = "❌ $label didn't wake: check 'Turn on via Wi-Fi' in its settings"
        Diag.warn("cast-target", "$label: did not answer within ${WAKE_TIMEOUT_MS}ms of the wake; $failure")
        false
    }

    fun watch(everyMs: Long = WATCH_MS) {
        if (!watching.compareAndSet(false, true)) return
        thread(name = "tv-power-$label", isDaemon = true) {
            while (true) {
                runCatching { runBlocking { refresh() } }
                    .onFailure { Diag.warn("cast-target", "$label: power check failed", it) }
                Thread.sleep(everyMs)
            }
        }
    }

    private fun sendPackets() {
        sentWake = true
        val packet = WakeOnLan.magicPacket(mac)
        var delivered = 0
        broadcasts.forEach { address ->
            WakeOnLan.PORTS.forEach { port ->
                runCatching { sender.send(packet, address, port) }
                    .onSuccess { delivered++ }
                    .onFailure { Diag.warn("cast-target", "$label: wake packet to $address:$port not sent", it) }
            }
        }
        Diag.log("cast-target", "$label: wake packets sent=$delivered of ${broadcasts.size * WakeOnLan.PORTS.size}")
    }

    private companion object {
        const val WAKE_TIMEOUT_MS = 25_000L
        const val POLL_MS = 1_000L
        const val RESEND_MS = 5_000L
        const val WATCH_MS = 5_000L
    }
}
