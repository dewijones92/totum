package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.WebOsTv

internal class TvPowerProbe(
    private val webos: WebOsTv?,
    private val ports: List<ReceiverProbe>,
) {
    @Volatile var evidence: String? = null
        private set

    suspend fun answers(): Boolean {
        val reading = webos?.panelReading()
        val open = ports.filter { it.answers() }
        val portSaid = "cast port ${ports.joinToString { it.where }} " + if (open.isEmpty()) "silent" else "answers"
        val webosSaid = reading?.let { "webOS ${it.said}; " }.orEmpty()
        if (webos != null && webos.paired) {
            evidence = "$webosSaid$portSaid (not used: webOS is paired, so it decides)"
            return reading?.on == true
        }
        evidence = webosSaid + portSaid + if (webos == null) "" else " (webOS is not paired, so the port decides)"
        return open.isNotEmpty()
    }
}
