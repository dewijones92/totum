package com.dewijones92.totum.playback.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.dewijones92.totum.common.Diag
import java.io.File
import java.net.Inet4Address

internal class CastHost(private val context: Context) {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    fun baseUrl(port: Int): String? {
        override()?.let {
            Diag.log("cast", "advertising the debug address $it (from ${OVERRIDE_FILE})")
            return it
        }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null

        @Suppress("DEPRECATION")
        val networks = cm.allNetworks.map { network ->
            val caps = cm.getNetworkCapabilities(network)
            CastNetwork(
                wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true,
                ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true,
                vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true,
                ipv4 = cm.getLinkProperties(network)?.linkAddresses.orEmpty().map { it.address }
                    .filter { it is Inet4Address && !it.isLoopbackAddress }.mapNotNull { it.hostAddress },
            )
        }
        val address = CastAddress.choose(networks)
        Diag.log(
            "cast",
            "address for the TV: ${address ?: "none"} from ${networks.size} network(s) " +
                "[${networks.joinToString("; ") { it.label }}]",
        )
        if (address == null) {
            Diag.warn("cast", "no IPv4 address on Wi-Fi or Ethernet (a VPN's does not count) to advertise to the TV")
            return null
        }
        return "http://$address:$port"
    }

    private fun override(): String? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return null
        return File(
            context.filesDir,
            OVERRIDE_FILE
        ).takeIf { it.exists() }?.readText()?.trim()?.trimEnd('/')?.ifEmpty { null }
    }

    @SuppressLint("WakelockTimeout", "Wakelock")
    @Synchronized
    fun hold(active: Boolean) {
        if (active && wakeLock == null) {
            wakeLock = context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "totum:cast-stream")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            @Suppress("DEPRECATION")
            wifiLock = context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "totum:cast-stream")?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
            Diag.log("cast", "holding a wake lock and a Wi-Fi lock while the TV streams from the phone")
        } else if (!active && wakeLock != null) {
            runCatching { wakeLock?.release() }
            runCatching { wifiLock?.release() }
            wakeLock = null
            wifiLock = null
            Diag.log("cast", "released the wake and Wi-Fi locks")
        }
    }

    private companion object {
        const val OVERRIDE_FILE = "cast-base-url.txt"
    }
}
