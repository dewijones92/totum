package com.dewijones92.totum.exsurge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.edit
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.ThrottledChanges
import com.dewijones92.totum.stats.NudgeWhat
import com.dewijones92.totum.stats.StatEvent
import com.dewijones92.totum.stats.StatsLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class FlatPhoneNudge(
    private val context: Context,
    private val exsurge: ExsurgeController,
    private val scope: CoroutineScope,
    private val stats: StatsLog? = null,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : SensorEventListener {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val accelerometer: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val stillness = FlatStillness()
    private var listening = false
    private var lastNudgeAt: Instant? = null
    private var notYet = ThrottledChanges(NOT_YET_LOG_GAP_MS)
    private val glyph by lazy { Icon.createWithBitmap(SurgiusPainter.glyphBitmap(ICON_PX)) }

    fun start() {
        if (accelerometer == null) {
            Diag.warn(TAG, "dewidebug flat-phone nudge unavailable: this device has no accelerometer")
            return
        }
        ensureChannel()
        scope.launch {
            while (isActive) {
                check()
                delay(CHECK_MS)
            }
        }
    }

    private fun check() {
        val settings = exsurge.view.value.settings
        val now = clock()
        val muted = mutedOn(context) == now.atZone(zone()).toLocalDate()
        val wanted = !settings.enabled && settings.flatNudge && settings.isActiveAt(now, zone()) && !muted
        if (wanted == listening) return
        listening = wanted
        if (wanted) {
            stillness.reset()
            val ok = sensors?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL, BATCH_US) == true
            Diag.log(
                TAG,
                "dewidebug flat-phone nudge listening=$ok (Exsurge off, in active hours) " +
                    "sensor=${accelerometer?.name} wakeUp=${accelerometer?.isWakeUpSensor}",
            )
        } else {
            sensors?.unregisterListener(this)
            Diag.log(
                TAG,
                "dewidebug flat-phone nudge stopped listening [enabled=${settings.enabled} " +
                    "nudge=${settings.flatNudge} activeNow=${settings.isActiveAt(now, zone())} mutedToday=$muted]",
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val flatFor = stillness.feed(event.values[0], event.values[1], event.values[2], event.timestamp / NANOS_PER_MS)
        val now = clock()
        val muted = mutedOn(context) == now.atZone(zone()).toLocalDate()
        val decision = flatNudgeFor(exsurge.view.value.settings, flatFor, now, zone(), lastNudgeAt, muted)
        when (decision) {
            FlatNudge.Nudge -> {
                lastNudgeAt = now
                notYet = ThrottledChanges(NOT_YET_LOG_GAP_MS)
                post(exsurge.view.value.settings.flatNudgeMinutes)
                stats?.record(StatEvent.Nudge(now.toEpochMilli(), NudgeWhat.POSTED))
                Diag.log(TAG, "dewidebug flat-phone nudge posted: flat and still for ${flatFor?.div(MS_PER_S)}s")
            }
            is FlatNudge.Not -> {
                val kind = if (decision.why.startsWith("flat for")) "flat for" else decision.why
                notYet.offer(kind, now.toEpochMilli())?.let { folded ->
                    val flips = if (folded > 0) " ($folded quick changes folded in since the last line)" else ""
                    Diag.log(TAG, "dewidebug flat-phone nudge not yet: ${decision.why}$flips")
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.exsurge_channel_nudge),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, BUZZ_MS)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun post(minutes: Int) {
        val turnOn = Notification.Action.Builder(
            glyph,
            context.getString(R.string.exsurge_action_turn_on),
            ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.TURN_ON),
        ).build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(glyph)
            .setContentTitle(context.getString(R.string.exsurge_nudge_title))
            .setContentText(context.resources.getQuantityString(R.plurals.exsurge_nudge_text, minutes, minutes))
            .setContentIntent(ExsurgeNotifications.screenIntent(context))
            .setAutoCancel(true)
            .addAction(turnOn)
            .addAction(
                Notification.Action.Builder(
                    glyph,
                    context.getString(R.string.exsurge_nudge_not_today),
                    ExsurgeActionReceiver.pending(context, ExsurgeActionReceiver.NUDGE_NOT_TODAY),
                ).build(),
            )
            .build()
        manager.notify(NUDGE_ID, notification)
    }

    companion object {
        private const val PREFS = "totum_exsurge_nudge"
        private const val MUTED_ON = "muted_on"

        fun muteToday(context: Context, today: LocalDate) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(MUTED_ON, today.toString()) }
            context.getSystemService(NotificationManager::class.java).cancel(NUDGE_ID)
            Diag.log(TAG, "dewidebug flat-phone nudge muted for $today")
        }

        fun dismiss(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(NUDGE_ID)

        private fun mutedOn(context: Context): LocalDate? =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MUTED_ON, null)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

        private const val TAG = "Exsurge"
        private const val CHANNEL = "exsurge_nudge"
        private const val NUDGE_ID = 7304
        private const val BUZZ_MS = 200L
        private const val CHECK_MS = 60_000L
        private const val BATCH_US = 30_000_000
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_S = 1_000L
        private const val ICON_PX = 96
    }
}

private const val NOT_YET_LOG_GAP_MS = 60_000L
