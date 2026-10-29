package com.dewijones92.totum.screens

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

fun startScreenReady(context: Context, wakeThePage: () -> Unit): ScreenReadiness {
    val app = context.applicationContext
    val manager = app.getSystemService(NotificationManager::class.java)
    val readiness = ScreenReadiness { ready, why ->
        Diag.log(TAG, "PC screen ${if (ready) "ready: posting the notice" else "not ready: notice cleared"} ($why)")
        if (ready) {
            wakeThePage()
            manager.notify(NOTICE_ID, notice(app, manager))
        } else {
            manager.cancel(NOTICE_ID)
        }
    }
    val battery = ContextCompat.registerReceiver(
        app,
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                readiness.charging(intent.action == Intent.ACTION_POWER_CONNECTED)
            }
        },
        IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        },
        ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    Diag.log(TAG, "PC screen readiness watching power and Wi-Fi (receiver=${battery != null})")
    val sticky = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    readiness.charging((sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0)
    val wifiNetworks = mutableSetOf<Network>()
    app.getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
        NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                synchronized(wifiNetworks) { wifiNetworks += network }
                readiness.wifi(true)
            }

            override fun onLost(network: Network) {
                val left = synchronized(wifiNetworks) {
                    wifiNetworks -= network
                    wifiNetworks.isNotEmpty()
                }
                readiness.wifi(left)
            }
        },
    )
    if (synchronized(wifiNetworks) { wifiNetworks.isEmpty() }) readiness.wifi(false)
    return readiness
}

private fun notice(context: Context, manager: NotificationManager): Notification {
    manager.createNotificationChannel(
        NotificationChannel(
            CHANNEL,
            context.getString(R.string.screen_ready_channel),
            NotificationManager.IMPORTANCE_MIN
        ),
    )
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(
            context,
            NOTICE_ID,
            it,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
    return Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_stat_screen)
        .setContentTitle(context.getString(R.string.screen_ready_title))
        .setContentText(context.getString(R.string.screen_ready_text))
        .setStyle(Notification.BigTextStyle().bigText(context.getString(R.string.screen_ready_text)))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(open)
        .build()
}

private const val TAG = "screen"
private const val CHANNEL = "screen_ready"
private const val NOTICE_ID = 7_501
