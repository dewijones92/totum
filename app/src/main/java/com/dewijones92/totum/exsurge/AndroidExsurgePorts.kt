package com.dewijones92.totum.exsurge

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackController
import com.dewijones92.totum.playback.PlaybackInterruption
import com.dewijones92.totum.reminders.kit.AlarmVoice
import com.dewijones92.totum.reminders.kit.Buzzer
import com.dewijones92.totum.reminders.kit.ExactAlarm
import com.dewijones92.totum.reminders.kit.Utterance
import java.time.Instant

class AndroidExsurgePorts(
    private val context: Context,
    private val playback: () -> PlaybackController,
    private val alarmRinging: () -> Boolean = { false },
    private val interruption: () -> PlaybackInterruption,
) : ExsurgePorts {
    private val notifications = ExsurgeNotifications(context)
    private val voice = AlarmVoice(context, ExsurgeController.TAG)
    private val alarm = ExactAlarm(context, ExsurgeController.TAG, "exsurge")
    private val buzzer = Buzzer(context, ExsurgeController.TAG)

    @SuppressLint("MissingPermission")
    override fun scheduleWake(at: Instant?) =
        alarm.schedule(at, ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.TICK))

    override fun showTakeover(request: TakeoverRequest) {
        if (alarmRinging()) {
            Diag.log(
                ExsurgeController.TAG,
                "dewidebug exsurge takeover call ${request.call} held: a daily alarm is ringing"
            )
            return
        }
        if (TakeoverActivity.resumed) {
            val call = request.call
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge takeover already on screen; no heads-up for call $call")
        } else {
            notifications.showSummons(request)
        }
        val overApps = request.overOtherApps && Settings.canDrawOverlays(context)
        val fullScreen = context.getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge takeover #${request.summonsId} call=${request.call} " +
                "snoozeMinutes=${request.snoozeMinutes} " +
                "overlay=$overApps fullScreenIntent=$fullScreen"
        )
        if (overApps) {
            runCatching { context.startActivity(TakeoverActivity.intent(context)) }
                .onFailure {
                    Diag.warn(
                        ExsurgeController.TAG,
                        "dewidebug exsurge direct takeover refused; the notification remains",
                        it
                    )
                }
        }
    }

    override fun hideTakeover() {
        notifications.cancelSummons()
        TakeoverActivity.finishAll()
    }

    override fun speak(cue: Cue, summonsId: Long, volumePercent: Int) {
        if (alarmRinging()) {
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge voice $cue held: a daily alarm is ringing")
            return
        }
        voice.say(Utterance.Clip(clipFor(cue, summonsId), "exsurge $cue"), volumePercent)
    }

    override fun buzz(haptic: Haptic) = buzzer.buzz("exsurge $haptic", haptic.waveform)

    override fun openDestination(settings: ExsurgeSettings): Boolean {
        val launch = destinationIntent(context, settings)
        if (launch == null) {
            val message = context.getString(R.string.exsurge_destination_missing, settings.destinationPackage)
            Handler(Looper.getMainLooper()).post { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
            val missing = settings.destinationPackage
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge destination $missing not installed")
            return false
        }
        return runCatching { context.startActivity(launch) }
            .onFailure {
                Diag.warn(ExsurgeController.TAG, "dewidebug exsurge could not open ${settings.destinationPackage}", it)
            }
            .isSuccess
    }

    override fun pausePlayback(): Boolean = interruption().interrupt()

    override fun resumePlayback(): String = interruption().release().toString()

    override fun continueTotum(): String {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
        val controller = playback()
        val state = controller.state.value ?: return "nothing queued; opened Totum"
        controller.setPlaying(true)
        return "resume requested item=${state.itemId.value} at ${state.positionMs}ms; opened Totum"
    }

    override fun viewChanged(view: ExsurgeView) {
        ExsurgeBannerService.reconcile(context, view, notifications)
        ExsurgeTileService.refresh(context)
    }

    companion object {
        const val LOQUAX_ROUTE_EXTRA = "hanzi_route"

        fun destinationIntent(context: Context, settings: ExsurgeSettings): Intent? =
            context.packageManager.getLaunchIntentForPackage(settings.destinationPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (settings.destinationRoute.isNotEmpty()) putExtra(LOQUAX_ROUTE_EXTRA, settings.destinationRoute)
            }

        fun destinationInstalled(context: Context, packageName: String): Boolean =
            context.packageManager.getLaunchIntentForPackage(packageName) != null

        fun hasStepSensor(context: Context): Boolean =
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_STEP_COUNTER)
    }
}

internal fun clipFor(cue: Cue, summonsId: Long): Int = when (cue) {
    Cue.SUMMON -> R.raw.exsurge_summon
    Cue.SUMMON_LOUDER -> R.raw.exsurge_summon_louder
    Cue.SUMMON_ORATION -> R.raw.exsurge_summon_oration
    Cue.GO -> R.raw.exsurge_go
    Cue.RISEN -> R.raw.exsurge_risen
    Cue.TWO_MINUTES -> R.raw.exsurge_two_minutes
    Cue.FREE -> if (summonsId % 2 == 0L) R.raw.exsurge_free_1 else R.raw.exsurge_free_2
    Cue.SKIPPED -> R.raw.exsurge_skipped
    Cue.PROMOTED -> R.raw.exsurge_promoted
}
