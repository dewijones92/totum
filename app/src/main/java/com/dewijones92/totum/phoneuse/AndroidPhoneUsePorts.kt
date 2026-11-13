package com.dewijones92.totum.phoneuse

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.net.toUri
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.kit.OverApps
import com.dewijones92.totum.stats.NudgeBlock
import com.dewijones92.totum.stats.RawKind
import com.dewijones92.totum.stats.RawUsage

internal fun rawKindOf(androidEventType: Int): RawKind? = when (androidEventType) {
    UsageEvents.Event.SCREEN_INTERACTIVE -> RawKind.SCREEN_ON
    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> RawKind.SCREEN_OFF
    UsageEvents.Event.KEYGUARD_HIDDEN -> RawKind.KEYGUARD_HIDDEN
    UsageEvents.Event.ACTIVITY_RESUMED -> RawKind.APP_FOREGROUND
    UsageEvents.Event.ACTIVITY_PAUSED -> RawKind.APP_BACKGROUND
    else -> null
}

fun usageAccessIntent(context: Context): Intent =
    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:${context.packageName}".toUri())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

class AndroidPhoneUsePorts(private val context: Context) : PhoneUsePorts {
    private val usage = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val nudges = UnlockNudgeNotifications(context)

    private val cameraApps by lazy {
        packagesHandling(
            Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            Intent(MediaStore.ACTION_VIDEO_CAPTURE),
        )
    }

    private val navigationApps by lazy {
        packagesHandling(
            Intent(Intent.ACTION_VIEW, "google.navigation:q=0,0".toUri()),
            Intent(Intent.ACTION_VIEW, "geo:0,0".toUri()),
        )
    }

    override fun accessGranted(): Boolean = appOps.unsafeCheckOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName,
    ) == AppOpsManager.MODE_ALLOWED

    override fun usageEvents(fromMs: Long, toMs: Long): List<RawUsage> {
        val events = usage.queryEvents(fromMs, toMs)
        val event = UsageEvents.Event()
        val raw = mutableListOf<RawUsage>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            rawKindOf(event.eventType)?.let { raw += RawUsage(event.timeStamp, it, event.packageName) }
        }
        return raw
    }

    override fun screenOn(): Boolean = power.isInteractive

    override fun inCall(): Boolean = audio.mode in CALL_MODES

    override fun appBlock(packageName: String?): NudgeBlock? = when {
        packageName == null -> null
        packageName in cameraApps -> NudgeBlock.CAMERA
        packageName in navigationApps -> NudgeBlock.NAVIGATION
        else -> null
    }

    override fun listenForUnlocks(onUnlock: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Diag.log(PhoneUse.TAG, "dewidebug phoneuse USER_PRESENT received")
                onUnlock()
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_USER_PRESENT), Context.RECEIVER_NOT_EXPORTED)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }

    override fun showNudge(request: NudgeRequest) {
        nudges.show(request)
        val directly = OverApps.granted(context) &&
            OverApps.start(context, PhoneUse.TAG, UnlockNudgeActivity.intent(context, request), "unlock nudge")
        Diag.log(
            PhoneUse.TAG,
            "dewidebug phoneuse nudge shown: notification posted; started directly over other apps=$directly " +
                "(display over other apps granted=${OverApps.granted(context)})",
        )
    }

    override fun hideNudge() {
        nudges.cancel()
        UnlockNudgeActivity.finishAll()
    }

    private fun packagesHandling(vararg intents: Intent): Set<String> = intents.flatMap { intent ->
        context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .map { it.activityInfo.packageName }
    }.toSet().also { Diag.log(PhoneUse.TAG, "dewidebug phoneuse apps for ${intents.map { it.action }}: $it") }

    private companion object {
        val CALL_MODES = setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_CALL_SCREENING,
        )
    }
}
