package com.dewijones92.totum.treat

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.R
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.di.AppContainer
import com.dewijones92.totum.innertube.playlists.YouTubePlaylists
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.pins.PinRun
import com.dewijones92.totum.pins.playlistRun

internal fun treatFor(c: AppContainer, context: Context): Treat = Treat(
    store = SharedPrefsTreatStore(context),
    pins = { c.pinStore.pins.value },
    ports = TreatPorts(
        targets = c.targets.targets,
        fadeSeconds = c.targets::fadeSeconds,
        content = { pin -> treatContentOf(pin, c.youTubePlaylists) },
        play = { id, content ->
            c.targets.playOnly(id, content.items, content.group(), "the treat button")
        },
        awaitStarted = { id -> c.targets.awaitStart(id, c.targets.targetOf(id)?.label ?: id) },
        arm = { id, ask -> c.targets.control(id, TargetControl(TargetAction.SLEEP, sleep = ask)) },
        stopNow = c.targets::endPlayback,
        subject = { title, tv -> context.getString(R.string.treat_subject, title, tv) },
        detail = { minutes, end -> context.getString(treatDetailRes(end), minutes) },
        notice = TreatNotifier(context),
    ),
    operations = c.operations,
    scope = c.applicationScope,
)

internal fun treatDetailRes(end: TreatEnd): Int = when (end) {
    TreatEnd.SWITCH_OFF -> R.string.treat_detail_switch_off
    TreatEnd.PAUSE -> R.string.treat_detail_pause
    TreatEnd.STOP -> R.string.treat_detail_stop
}

internal suspend fun treatContentOf(pin: Pin, playlists: YouTubePlaylists): TreatContentResult = when (pin) {
    is Pin.Item -> pin.playable()
        ?.let { TreatContentResult.Found(TreatContent(pin.title, listOf(it), null, null)) }
        ?: TreatContentResult.Failed("the pinned item cannot be rebuilt")
    is Pin.Playlist -> when (val run = playlistRun(playlists, pin)) {
        is PinRun.Failed -> TreatContentResult.Failed(run.why)
        is PinRun.Tracks -> TreatContentResult.Found(TreatContent(pin.title, run.items, run.group.id, run.group.title))
    }
    else -> TreatContentResult.Failed("a ${pin::class.simpleName} cannot be sent to a TV by the treat button")
}

internal class TreatNotifier(private val context: Context) : TreatNotice {
    private val manager = NotificationManagerCompat.from(context)

    override fun on(title: String, tv: String, endsAtMs: Long) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return Diag.log("treat", "dewidebug treat notification not shown: notifications are not allowed")
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        runCatching {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.treat_channel_name))
                    .build(),
            )
            val left = (endsAtMs - System.currentTimeMillis()).coerceAtLeast(0)
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_new_content)
                    .setContentTitle(context.getString(R.string.treat_subject, title, tv))
                    .setContentText(context.getString(R.string.treat_notice_text))
                    .setContentIntent(open)
                    .setWhen(endsAtMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setTimeoutAfter(left + GRACE_MS)
                    .setSilent(true)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .build(),
            )
        }.onSuccess { Diag.log("treat", "dewidebug treat notification posted for \"$title\" on $tv until $endsAtMs") }
            .onFailure { Diag.warn("treat", "dewidebug treat notification failed", it) }
    }

    override fun off(why: String) {
        manager.cancel(NOTIFICATION_ID)
        Diag.log("treat", "dewidebug treat notification cleared ($why)")
    }

    private companion object {
        const val CHANNEL_ID = "treat"
        const val NOTIFICATION_ID = 7_760_002
        const val GRACE_MS = 60_000L
    }
}
