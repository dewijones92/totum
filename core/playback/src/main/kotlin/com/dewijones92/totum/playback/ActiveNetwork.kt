package com.dewijones92.totum.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

public fun Context.activeNetworkIsMetered(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return true
    val network = cm.activeNetwork ?: return true
    val caps = cm.getNetworkCapabilities(network) ?: return true
    return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
