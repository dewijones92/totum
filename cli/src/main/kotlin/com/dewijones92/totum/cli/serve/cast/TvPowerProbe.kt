package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.serve.power.PanelReading
import com.dewijones92.totum.cli.serve.power.WebOsTv

internal class TvPowerProbe(
    private val webos: WebOsTv?,
    private val ports: List<ReceiverProbe>,
) {
    @Volatile var evidence: String? = null
        private set

    @Volatile var unsure: Boolean = false
        private set

    private class Look(
        val webos: PanelReading?,
        val decidedByWebOs: Boolean,
        val open: List<ReceiverProbe>,
        val probed: List<ReceiverProbe>,
    ) {
        val portSaid: String = "cast port " +
            open.ifEmpty { probed }.joinToString { it.where }.ifEmpty { "(none known)" }

        val unknown: Boolean get() = decidedByWebOs && webos?.on == null && open.isNotEmpty()

        val unknownSaid: String get() = "$portSaid answers while webOS ${webos?.said}: unknown, maybe on"
    }

    private suspend fun look(): Look {
        val reading = webos?.panelReading()
        return Look(reading, webos != null && webos.paired, ports.filter { it.answers() }, ports)
    }

    suspend fun signOfLife(): String? {
        val seen = look()
        return when {
            seen.webos?.on == true && seen.decidedByWebOs -> "webOS says the panel is Active"
            seen.unknown -> seen.unknownSaid
            seen.open.isNotEmpty() -> "${seen.portSaid} answers while webOS " + (seen.webos?.said ?: "is not asked") +
                ": maybe on"
            else -> null
        }
    }

    suspend fun answers(): Boolean {
        val seen = look()
        val portSaid = seen.portSaid + if (seen.open.isEmpty()) " silent" else " answers"
        val webosSaid = seen.webos?.let { "webOS ${it.said}; " }.orEmpty()
        unsure = seen.unknown
        if (seen.decidedByWebOs) {
            evidence = "$webosSaid$portSaid " + if (seen.unknown) {
                "(webOS is paired but could not be asked: unknown, maybe on)"
            } else {
                "(not used: webOS is paired, so it decides)"
            }
            return seen.webos?.on == true
        }
        evidence = webosSaid + portSaid + if (webos == null) "" else " (webOS is not paired, so the port decides)"
        return seen.open.isNotEmpty()
    }
}
