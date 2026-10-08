package com.dewijones92.totum.exsurge

import android.app.Notification
import android.content.Context
import android.content.Intent
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.LiveUpdate
import com.dewijones92.totum.reminders.kit.PinnedChannel

class ExsurgeLiveUpdate(private val context: Context) {

    fun apply(builder: Notification.Builder, chip: BannerChip) =
        LiveUpdate.apply(builder, (chip as? BannerChip.Countdown)?.until, chipText(chip))

    fun log(view: ExsurgeView, chip: BannerChip, notification: Notification) {
        val line = LiveUpdate.describe(context, notification).replace(" sdk=", " chip=${describe(chip)} sdk=")
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
        private val BANNER_IDS = setOf(ExsurgeNotifications.BANNER_ID, ExsurgeNotifications.FOREGROUND_ID)

        @Volatile
        private var lastPosted: String? = null

        fun allowed(context: Context): Boolean? = LiveUpdate.allowed(context)

        fun settingsIntent(context: Context): Intent? = LiveUpdate.settingsIntent(context)

        fun diagnostics(context: Context): Map<String, String> = mapOf(
            "exsurge.liveUpdate.allowed" to (allowed(context)?.toString() ?: "unsupported"),
            "exsurge.liveUpdate.lastPosted" to (lastPosted ?: "nothing posted yet"),
            "exsurge.liveUpdate.posted" to LiveUpdate.posted(context, BANNER_IDS, "no banner posted"),
            "exsurge.bannerChannel" to PinnedChannel.describe(context, ExsurgeNotifications.BANNER_CHANNEL),
        )
    }
}
