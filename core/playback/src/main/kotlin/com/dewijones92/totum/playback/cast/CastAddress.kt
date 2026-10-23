package com.dewijones92.totum.playback.cast

internal data class CastNetwork(
    val wifi: Boolean,
    val ethernet: Boolean,
    val vpn: Boolean,
    val ipv4: List<String>,
    val routes: List<String> = emptyList(),
) {
    val label: String
        get() {
            val kinds = listOfNotNull("wifi".takeIf { wifi }, "ethernet".takeIf { ethernet }, "vpn".takeIf { vpn })
                .ifEmpty { listOf("other") }.joinToString("+")
            val addresses = ipv4.ifEmpty { listOf("no-ipv4") }.joinToString(",")
            val routing = if (vpn && routes.isNotEmpty()) " routes=${routes.joinToString(",")}" else ""
            return "$kinds $addresses$routing"
        }
}

internal object CastAddress {

    fun vpnCarries(address: String, networks: List<CastNetwork>): Boolean =
        networks.any { network -> network.vpn && network.routes.any { covers(it, address) } }

    internal fun covers(prefix: String, address: String): Boolean {
        val (net, bits) = prefix.split('/').takeIf { it.size == 2 } ?: return false
        val length = bits.toIntOrNull()?.takeIf { it in 0..IPV4_BITS } ?: return false
        val network = ipv4(net) ?: return false
        val host = ipv4(address) ?: return false
        if (length == 0) return true
        val mask = (-1L shl (IPV4_BITS - length)) and FULL_MASK
        return network and mask == host and mask
    }

    private fun ipv4(text: String): Long? {
        val parts = text.split('.').map { it.toIntOrNull() ?: return null }
        if (parts.size != IPV4_PARTS || parts.any { it !in 0..OCTET_MAX }) return null
        return parts.fold(0L) { acc, part -> (acc shl OCTET_BITS) or part.toLong() }
    }

    private const val IPV4_BITS = 32
    private const val IPV4_PARTS = 4
    private const val OCTET_BITS = 8
    private const val OCTET_MAX = 255
    private const val FULL_MASK = 0xFFFFFFFFL

    fun choose(networks: List<CastNetwork>): String? {
        val local = networks.filter { !it.vpn && it.ipv4.isNotEmpty() }
        return (local.firstOrNull { it.wifi } ?: local.firstOrNull { it.ethernet })?.ipv4?.first()
    }
}
