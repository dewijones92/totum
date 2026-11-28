package com.dewijones92.totum.cli.serve.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

internal class ReceiverProbe(
    private val host: String,
    private val port: Int = CAST_PORT,
    private val timeoutMs: Int = CONNECT_MS,
) {
    val where: String get() = "$host:$port"

    suspend fun answers(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) } }.isSuccess
    }

    private companion object {
        const val CAST_PORT = 8009
        const val CONNECT_MS = 1_500
    }
}
