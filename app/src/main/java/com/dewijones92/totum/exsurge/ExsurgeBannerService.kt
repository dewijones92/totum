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
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag

class ExsurgeBannerService : Service(), SensorEventListener {
    private val handler = Handler(Looper.getMainLooper())
    private val exsurge get() = (application as TotumApplication).container.exsurge
    private var listening = false
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
        if (intent?.action == ACTION_REST) {
            Diag.log(ExsurgeController.TAG, "dewidebug exsurge banner service resting: detach notification, stop")
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = ExsurgeNotifications(this).banner(exsurge.view.value)
        val started = runCatching {
            startForeground(ExsurgeNotifications.BANNER_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
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
        startListening()
        handler.removeCallbacks(minuteTick)
        handler.postDelayed(minuteTick, REFRESH_MS)
        return START_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_DETACH)
        handler.removeCallbacks(minuteTick)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        listening = false
        countingSteps = false
        current = null
        running = false
        Diag.log(ExsurgeController.TAG, "dewidebug exsurge banner service destroyed")
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) exsurge.onStepCounter(event.values[0].toLong())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun startListening() {
        if (listening) return
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
        listening = sensors.registerListener(this, counter, SensorManager.SENSOR_DELAY_NORMAL, 0)
        countingSteps = listening
        current = this
        Diag.log(
            ExsurgeController.TAG,
            "dewidebug exsurge step counter registered=$listening wakeUp=${counter.isWakeUpSensor}"
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

        fun stepsPermitted(context: Context): Boolean =
            context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

        fun reconcile(context: Context, view: ExsurgeView, notifications: ExsurgeNotifications) {
            val state = view.memory.state
            val resting = state is ExsurgeState.Off || state is ExsurgeState.Dormant || state is ExsurgeState.Paused
            val wantService = view.settings.enabled && !resting
            when {
                !view.settings.enabled -> {
                    if (running) context.stopService(Intent(context, ExsurgeBannerService::class.java))
                    notifications.cancelBanner()
                }
                wantService && !running && stepsPermitted(context) -> start(context, notifications, view)
                wantService || !running -> notifications.showBanner(view)
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
