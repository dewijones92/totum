package com.dewijones92.totum.exsurge

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import java.time.Instant

class ExsurgeBannerService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val exsurge get() = (application as TotumApplication).container.exsurge
    private var listeningBatchUs: Int? = null
    private var destroyed = false
    private val minuteTick = object : Runnable {
        override fun run() {
            exsurge.refresh()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge banner service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REST && !needsService(exsurge.view.value)) return rest()
        val notification = ExsurgeNotifications(this).banner(exsurge.view.value)
        val started = runCatching {
            startForeground(
                ExsurgeNotifications.FOREGROUND_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            )
        }.onFailure {
            Diag.warn(
                ExsurgeController.TAG,
                "dewidebug exsurge banner could not go foreground; steps will not be counted",
                it
            )
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!needsService(exsurge.view.value)) return rest()
        ExsurgeNotifications(this).hideIdleBanner()
        tuneSteps(exsurge.view.value.memory.state)
        handler.removeCallbacks(minuteTick)
        handler.postDelayed(minuteTick, REFRESH_MS)
        return START_STICKY
    }

    private fun rest(): Int {
        val view = exsurge.view.value
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge banner service resting: idle banner state=${view.memory.state.label()}, stop",
        )
        ExsurgeNotifications(this).showBanner(view)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        handler.removeCallbacks(minuteTick)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        listeningBatchUs = null
        destroyed = true
        countingSteps = false
        current = null
        running = false
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge banner service destroyed")
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val ageNanos = (SystemClock.elapsedRealtimeNanos() - event.timestamp).coerceAtLeast(0)
        exsurge.onStepCounter(event.values[0].toLong(), at = Instant.now().minusNanos(ageNanos))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun tuneSteps(state: ExsurgeState) {
        if (destroyed) {
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge step tuning skipped: this banner service is stopping")
            return
        }
        if (!stepsPermitted(this)) {
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge steps not counted: ACTIVITY_RECOGNITION not granted")
            return
        }
        val sensors = getSystemService(SensorManager::class.java)
        val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER, true)
            ?: sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (counter == null) {
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge steps not counted: no step counter on this device")
            return
        }
        val moving = state is ExsurgeState.Rising || state is ExsurgeState.OnBreak
        val batching = if (counter.isWakeUpSensor && !moving) WAKE_UP_BATCH_US else 0
        val was = listeningBatchUs
        if (was == batching) return
        if (was != null) sensors.unregisterListener(this)
        val registered = sensors.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, batching)
        listeningBatchUs = if (registered) batching else null
        countingSteps = registered
        current = this
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge step counter registered=$registered wakeUp=${counter.isWakeUpSensor} " +
                "batchUs=$batching (was ${was ?: "unregistered"}) state=${state.label()} moving=$moving"
        )
    }

    companion object {
        @Volatile
        var running: Boolean = false
            private set

        @Volatile
        var countingSteps: Boolean = false
            private set

        private const val ACTION_REST = "com.dewijones92.totum.exsurge.REST"
        private var current: ExsurgeBannerService? = null

        fun flushSteps(): Boolean {
            val service = current ?: return false
            val sensors = service.getSystemService(SensorManager::class.java)
            return runCatching { sensors.flush(service) }.getOrDefault(false)
        }
        private const val REFRESH_MS = 60_000L
        private const val WAKE_UP_BATCH_US = 60_000_000

        fun stepsPermitted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        fun reconcile(context: Context, view: ExsurgeView, notifications: ExsurgeNotifications) {
            val state = view.memory.state
            val wantService = needsService(view)
            when {
                wantService && !running && stepsPermitted(context) -> start(context, notifications, view)
                wantService || !running -> {
                    notifications.showBanner(view, foreground = wantService && running)
                    current?.let { service -> service.handler.post { service.tuneSteps(state) } }
                }
                else -> {
                    notifications.showBanner(view)
                    runCatching {
                        context.startService(Intent(context, ExsurgeBannerService::class.java).setAction(ACTION_REST))
                    }.onFailure {
                        Diag.warn(ExsurgeController.TAG, "dewidebug exsurge could not rest the banner service", it)
                    }
                }
            }
        }

        private fun needsService(view: ExsurgeView): Boolean = when (view.memory.state) {
            ExsurgeState.Off, is ExsurgeState.Dormant, is ExsurgeState.Paused -> false
            else -> true
        }

        private fun start(context: Context, notifications: ExsurgeNotifications, view: ExsurgeView) {
            runCatching { context.startForegroundService(Intent(context, ExsurgeBannerService::class.java)) }
                .onSuccess {
                    Diag.log(
                        ExsurgeController.TAG,
                        "dewidebug exsurge banner service starting at state=${view.memory.state.label()}"
                    )
                }
                .onFailure {
                    Diag.warn(
                        ExsurgeController.TAG,
                        "dewidebug exsurge banner service refused; showing a plain sticky banner, steps not counted",
                        it
                    )
                    notifications.showBanner(view)
                }
        }
    }
}
