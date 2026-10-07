package com.dewijones92.totum.dailyalarms

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.reminders.Waveforms
import com.dewijones92.totum.reminders.kit.AlarmTone
import com.dewijones92.totum.reminders.kit.AlarmVoice
import com.dewijones92.totum.reminders.kit.Buzzer
import com.dewijones92.totum.reminders.kit.Utterance
import java.time.LocalTime

class RingService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tag = DailyAlarmController.TAG
    private val tone by lazy { AlarmTone(this, tag) }
    private val voice by lazy { AlarmVoice(this, tag) }
    private val buzzer by lazy { Buzzer(this, tag) }
    private var line: String? = null
    private val speakAgain = object : Runnable {
        override fun run() {
            line?.let { if (!voice.busy) voice.say(Utterance.Speech(it)) }
            handler.postDelayed(this, SPEAK_EVERY_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) return stop()
        val id = intent?.getStringExtra(EXTRA_ID) ?: return stop()
        val container = (application as TotumApplication).container
        val alarm = container.dailyAlarms.view.value.alarms.firstOrNull { it.id == id } ?: return stop()
        val time = intent.getStringExtra(EXTRA_TIME)?.let(LocalTime::parse) ?: alarm.defaultTime
        val notification = DailyAlarmNotifications(this).ring(alarm, time)
        val foreground = runCatching {
            startForeground(
                DailyAlarmNotifications.RING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
            )
        }.onFailure {
            Diag.warn(
                tag,
                "dewidebug dailyalarm ring could not go foreground; ringing anyway",
                it
            )
        }.isSuccess
        Diag.log(tag, "dewidebug dailyalarm ring start ${alarm.id} at $time foreground=$foreground")
        tone.start()
        buzzer.buzz("dailyalarm ${alarm.id}", RING_WAVEFORM, repeatFrom = 0)
        line = spokenLine(alarm.label, time)
        handler.removeCallbacks(speakAgain)
        handler.postDelayed(speakAgain, FIRST_SPEAK_MS)
        runCatching { startActivity(RingActivity.intent(this, alarm.id)) }
            .onFailure {
                Diag.log(
                    tag,
                    "dewidebug dailyalarm ring screen not started directly (${it.message}); full-screen intent instead"
                )
            }
        return START_NOT_STICKY
    }

    private fun stop(): Int {
        handler.removeCallbacks(speakAgain)
        line = null
        tone.stop()
        voice.stop()
        buzzer.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Diag.log(tag, "dewidebug dailyalarm ring stopped")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(speakAgain)
        tone.stop()
        voice.stop()
        buzzer.stop()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "com.dewijones92.totum.dailyalarms.STOP_RING"
        private const val EXTRA_ID = "dailyalarm.id"
        private const val EXTRA_TIME = "dailyalarm.time"
        private const val FIRST_SPEAK_MS = 1_500L
        private const val SPEAK_EVERY_MS = 9_000L
        private val RING_WAVEFORM = Waveforms.once(longArrayOf(800, 400, 800, 1_200))

        fun start(context: Context, alarmId: String, time: LocalTime) {
            val intent = Intent(context, RingService::class.java)
                .putExtra(EXTRA_ID, alarmId)
                .putExtra(EXTRA_TIME, time.toString())
            runCatching { context.startForegroundService(intent) }
                .onFailure { Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm ring service refused", it) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, RingService::class.java).setAction(ACTION_STOP)) }
                .onFailure { Diag.warn(DailyAlarmController.TAG, "dewidebug dailyalarm ring service stop failed", it) }
        }
    }
}
