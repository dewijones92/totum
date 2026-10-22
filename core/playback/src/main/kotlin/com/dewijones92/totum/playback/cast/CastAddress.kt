package com.dewijones92.totum.playback.cast

internal data class CastNetwork(
    val wifi: Boolean,
    val ethernet: Boolean,
    val vpn: Boolean,
    val ipv4: List<String>,
) {
    val label: String
        get() = listOfNotNull("wifi".takeIf { wifi }, "ethernet".takeIf { ethernet }, "vpn".takeIf { vpn })
            .ifEmpty { listOf("other") }.joinToString("+") + " ${ipv4.ifEmpty { listOf("no-ipv4") }.joinToString(",")}"
}

internal object CastAddress {

    fun choose(networks: List<CastNetwork>): String? {
        val local = networks.filter { !it.vpn && it.ipv4.isNotEmpty() }
        return (local.firstOrNull { it.wifi } ?: local.firstOrNull { it.ethernet })?.ipv4?.first()
    }
}
