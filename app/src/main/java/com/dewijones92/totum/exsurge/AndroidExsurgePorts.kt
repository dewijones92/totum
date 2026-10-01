package com.dewijones92.totum.exsurge

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.PlaybackInterruption
import java.time.Instant

class AndroidExsurgePorts(
    private val context: Context,
    private val interruption: () -> PlaybackInterruption,
) : ExsurgePorts {
    private val notifications = ExsurgeNotifications(context)
    private val voice = VoiceCues(context)
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private var scheduled: Instant? = null

    @SuppressLint("MissingPermission")
    override fun scheduleWake(at: Instant?) {
        if (at == scheduled && at?.isAfter(Instant.now()) != false) return
        val tick = ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.TICK)
        if (at == null) {
            alarms.cancel(tick)
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge alarm cancelled (was $scheduled)")
        } else if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), tick)
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge alarm exact at $at")
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), tick)
            Diag.warn(ExsurgeController.TAG, "dewidebug exsurge alarm INEXACT at $at: exact alarms not allowed")
        }
        scheduled = at
    }

    override fun showTakeover(request: TakeoverRequest) {
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
                "snoozesLeft=${request.snoozesLeft} " +
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

    override fun speak(cue: Cue, summonsId: Long, volumePercent: Int) = voice.play(cue, summonsId, volumePercent)

    override fun buzz(haptic: Haptic) {
        val pattern = when (haptic) {
            Haptic.SUMMONS -> SUMMONS_BUZZ
            Haptic.STEPS_ACCEPTED -> STEPS_BUZZ
            Haptic.RELEASE -> RELEASE_BUZZ
        }
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator.vibrate(
            VibrationEffect.createWaveform(pattern, -1),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM)
        )
    }

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

    override fun viewChanged(view: ExsurgeView) {
        ExsurgeBannerService.reconcile(context, view, notifications)
        ExsurgeTileService.refresh(context)
    }

    companion object {
        const val LOQUAX_ROUTE_EXTRA = "hanzi_route"
        private val SUMMONS_BUZZ = longArrayOf(0, 600, 200, 200, 200, 600)
        private val STEPS_BUZZ = longArrayOf(0, 80, 80, 80)
        private val RELEASE_BUZZ = longArrayOf(0, 100, 120, 200, 120, 400)

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

class VoiceCues(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val queue = ArrayDeque<Pair<Int, Float>>()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun play(cue: Cue, summonsId: Long, volumePercent: Int) {
        val volume = volumePercent / PERCENT
        queue.addLast(clipFor(cue, summonsId) to volume)
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge voice queued $cue volume=$volumePercent% queue=${queue.size} playing=${player != null}"
        )
        if (player == null) next()
    }

    private fun next() {
        val (clip, volume) = queue.removeFirstOrNull() ?: return release()
        if (focus == null) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge voice focus=${audio.requestAudioFocus(request)}")
            focus = request
        }
        val started = runCatching {
            val afd = context.resources.openRawResourceFd(clip)
            MediaPlayer().apply {
                setAudioAttributes(attributes)
                afd.use { setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                setVolume(volume, volume)
                setOnCompletionListener { done() }
                setOnErrorListener { _, what, extra ->
                    Diag.warn(ExsurgeController.TAG, "dewidebug exsurge voice error what=$what extra=$extra")
                    done()
                    true
                }
                prepare()
                start()
            }
        }.onFailure {
            Diag.warn(
                ExsurgeController.TAG,
                "dewidebug exsurge voice could not play clip $clip",
                it
            )
        }.getOrNull()
        player = started
        if (started == null) next()
    }

    private fun done() {
        player?.release()
        player = null
        next()
    }

    private fun release() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun clipFor(cue: Cue, summonsId: Long): Int = when (cue) {
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

    private companion object {
        const val PERCENT = 100f
    }
}
