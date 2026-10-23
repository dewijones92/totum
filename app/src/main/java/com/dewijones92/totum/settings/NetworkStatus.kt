package com.dewijones92.totum.settings

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.getSystemService
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.activeNetworkIsMetered
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Tells whether the active connection is metered (mobile data / hotspot) vs
 * unmetered (Wi-Fi), so playback can apply the right quality cap. Errs toward
 * "metered" (the data-saving side) when the state is unknown.
 */
class NetworkStatus(private val context: Context) {

    fun isMetered(): Boolean = context.activeNetworkIsMetered()

    /** Whether there is a validated connection right now — actually reaching the internet. */
    fun isOnline(): Boolean {
        val cm = context.getSystemService<ConnectivityManager>() ?: return false
        val caps = cm.activeNetwork?.let(cm::getNetworkCapabilities) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Suspends until there is a validated connection, returning at once when there already
     * is one.
     *
     * A callback rather than a poll, so playback resumes the moment the signal returns
     * instead of up to an interval later — coming out of a tunnel, that difference is the
     * whole user experience. VALIDATED specifically: `AVAILABLE` fires while a captive
     * portal or a still-associating Wi-Fi is technically "connected" but cannot carry a
     * byte, and resuming into that just fails again and spends a retry.
     */
    fun onlineChanges(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService<ConnectivityManager>()
        if (cm == null) {
            send(false)
            awaitClose()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(isOnline())
            }
        }
        send(isOnline())
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged().onEach { Diag.log("network", "online=$it, as the queue sees it") }

    suspend fun awaitOnline() {
        if (isOnline()) return
        val cm = context.getSystemService<ConnectivityManager>() ?: return
        Diag.log("playback", "waiting for a validated network")
        var registered: ConnectivityManager.NetworkCallback? = null
        try {
            suspendCancellableCoroutine { continuation ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                }
                registered = callback
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build()
                cm.registerNetworkCallback(request, callback)
                // Registering is not instant, so a network that arrived in between would
                // otherwise be missed and the wait would hang until the next change.
                if (isOnline() && continuation.isActive) continuation.resume(Unit)
            }
        } finally {
            // Unregistered on EVERY exit, not just cancellation: a callback left behind
            // outlives the wait it belonged to and would resume a later, unrelated one.
            registered?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        }
    }
}
