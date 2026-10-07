package com.dewijones92.totum.exsurge

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag

class ExsurgeLiveUpdate(private val context: Context) {

    fun apply(builder: Notification.Builder, chip: BannerChip) {
        when (chip) {
            is BannerChip.Countdown -> builder.setWhen(chip.until.toEpochMilli()).setShowWhen(true)
            else -> builder.setShowWhen(false)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return
        builder.setShortCriticalText(chipText(chip))
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return
        builder.setRequestPromotedOngoing(true)
    }

    fun log(view: ExsurgeView, chip: BannerChip, notification: Notification) {
        val requested = notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED, false)
        val promotable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            notification.hasPromotableCharacteristics()
        val line = "requested=$requested promotable=$promotable appAllowed=${allowed(context) ?: "unsupported"} " +
            "chip=${describe(chip)} sdk=${sdkLabel()}"
        if (line == lastPosted) return
        lastPosted = line
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge live update changed: $line state=${view.memory.state.label()}"
        )
    }

    private fun chipText(chip: BannerChip): String? = when (chip) {
        is BannerChip.Countdown -> null
        is BannerChip.Steps -> context.getString(R.string.exsurge_chip_steps, chip.steps, chip.needed)
        BannerChip.Go -> context.getString(R.string.exsurge_chip_go)
        BannerChip.Off -> context.getString(R.string.exsurge_chip_off)
        BannerChip.Asleep -> context.getString(R.string.exsurge_chip_asleep)
    }

    private fun describe(chip: BannerChip): String = when (chip) {
        is BannerChip.Countdown -> "countdown to ${chip.until}"
        else -> "\"${chipText(chip)}\""
    }

    companion object {
        private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
        private const val SDK_FULL_MULTIPLIER = 100_000
        private val BANNER_IDS = setOf(ExsurgeNotifications.BANNER_ID, ExsurgeNotifications.FOREGROUND_ID)

        @Volatile
        private var lastPosted: String? = null

        fun allowed(context: Context): Boolean? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
            } else {
                null
            }

        fun settingsIntent(context: Context): Intent? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                null
            }

        fun diagnostics(context: Context): Map<String, String> {
            val posted = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) {
                "unsupported"
            } else {
                runCatching {
                    context.getSystemService(NotificationManager::class.java).activeNotifications
                        .filter { it.id in BANNER_IDS }
                        .joinToString { posted ->
                            val promoted = posted.notification.flags and Notification.FLAG_PROMOTED_ONGOING != 0
                            "id=${posted.id} promoted=$promoted"
                        }
                        .ifEmpty { "no banner posted" }
                }.getOrElse { "unreadable: $it" }
            }
            return mapOf(
                "exsurge.liveUpdate.allowed" to (allowed(context)?.toString() ?: "unsupported"),
                "exsurge.liveUpdate.lastPosted" to (lastPosted ?: "nothing posted yet"),
                "exsurge.liveUpdate.posted" to posted,
            )
        }

        private fun sdkLabel(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                "${Build.VERSION.SDK_INT}.${Build.VERSION.SDK_INT_FULL % SDK_FULL_MULTIPLIER}"
            } else {
                Build.VERSION.SDK_INT.toString()
            }
    }
}
