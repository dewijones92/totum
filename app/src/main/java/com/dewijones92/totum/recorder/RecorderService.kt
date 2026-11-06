package com.dewijones92.totum.recorder

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dewijones92.totum.R
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.operations.OperationEnd
import com.dewijones92.totum.operations.OperationKind
import com.dewijones92.totum.operations.tracking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RecorderService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val notifications by lazy { RecorderNotifications(this) }
    private var live: Live? = null

    private class Live(
        val capture: ScreenRecording,
        val file: RecordingFile,
        val bubble: FaceBubble?,
        val sinceClock: Long,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(intent)
            ACTION_STOP -> {
                val why = intent.getStringExtra(EXTRA_WHY) ?: "stop was asked for"
                if (live == null) leave("stop arrived with nothing recording ($why)") else finish(why)
            }
            else -> leave("started with ${intent?.action ?: "no action"}, which means nothing")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (live != null) {
            Diag.log(TAG, "dewidebug recorder service destroyed while recording; finishing the file")
            finish("the service was destroyed")
        }
        RecorderState.set(RecorderStatus.Idle)
        RecorderTileService.refresh(this)
        super.onDestroy()
    }

    private class Plan(val mic: Boolean, val face: Boolean, val taps: TapsShown, val sinceMs: Long) {
        fun status(shown: TapsShown = taps) = RecorderStatus.Recording(sinceMs, mic, face, shown)

        fun foregroundTypes(): Int {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            if (mic) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (face) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            return types
        }
    }

    private fun planFor(intent: Intent, taps: SystemTaps): Plan {
        val asked = RecorderChoices(
            mic = intent.getBooleanExtra(EXTRA_MIC, false),
            face = intent.getBooleanExtra(EXTRA_FACE, false),
            taps = intent.getBooleanExtra(EXTRA_TAPS, false),
        )
        val overlay = Settings.canDrawOverlays(this)
        val cameraPermitted = granted(Manifest.permission.CAMERA)
        val plan = Plan(
            mic = asked.mic && granted(Manifest.permission.RECORD_AUDIO),
            face = asked.face && cameraPermitted && overlay,
            taps = when {
                !asked.taps -> TapsShown.OFF
                taps.permitted() -> TapsShown.BY_SYSTEM
                else -> TapsShown.IN_TOTUM
            },
            sinceMs = System.currentTimeMillis(),
        )
        Diag.log(
            TAG,
            "dewidebug recorder starting asked=$asked micUsable=${plan.mic} faceUsable=${plan.face} " +
                "overlayAllowed=$overlay cameraPermitted=$cameraPermitted taps=${plan.taps}",
        )
        return plan
    }

    @Suppress("TooGenericExceptionCaught")
    private fun begin(intent: Intent) {
        if (live != null) {
            Diag.log(TAG, "dewidebug recorder start asked while one is already running; ignored")
            return
        }
        val taps = SystemTaps(this)
        taps.restore()
        val plan = planFor(intent, taps)
        val foreground = runCatching {
            ServiceCompat.startForeground(
                this,
                RecorderNotifications.RECORDING_ID,
                notifications.recording(plan.status()),
                plan.foregroundTypes(),
            )
        }
        if (foreground.isFailure) {
            fail("Android would not let the recorder run in the foreground", foreground.exceptionOrNull(), null, null)
            return
        }
        val opened = Opened()
        try {
            live = startCapture(intent, plan, taps, opened)
        } catch (failure: Exception) {
            fail(failure.message ?: failure.javaClass.simpleName, failure, opened.file, opened.projection)
        }
    }

    private class Opened {
        var file: RecordingFile? = null
        var projection: MediaProjection? = null
    }

    private fun startCapture(intent: Intent, plan: Plan, taps: SystemTaps, opened: Opened): Live {
        val data = checkNotNull(intent.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)) {
            "the screen capture permission did not arrive"
        }
        val projection = checkNotNull(
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_RESULT, 0), data),
        ) { "Android would not hand over the screen capture" }
        opened.projection = projection
        val file = RecordingFile.create(this)
        opened.file = file
        val capture = ScreenRecording.begin(this, projection, file.descriptor.fileDescriptor, plan.mic, handler) {
            finish("the system ended the screen capture")
        }
        val bubble = if (plan.face) FaceBubble(this).takeIf { it.show() } else null
        if (plan.face && bubble == null) Diag.log(TAG, "dewidebug recorder recording without the face bubble")
        val shown = if (plan.taps == TapsShown.BY_SYSTEM && !taps.show()) TapsShown.IN_TOTUM else plan.taps
        val running = plan.status(shown)
        RecorderState.set(running)
        RecorderTileService.refresh(this)
        Diag.log(TAG, "dewidebug recorder recording file=${file.name} size=${capture.size} status=$running")
        return Live(capture, file, bubble, SystemClock.elapsedRealtime())
    }

    private fun finish(why: String) {
        val current = live ?: return
        live = null
        val tookMs = SystemClock.elapsedRealtime() - current.sinceClock
        Diag.log(TAG, "dewidebug recorder stopping why=\"$why\" afterMs=$tookMs")
        current.bubble?.hide()
        SystemTaps(this).restore()
        val wrote = current.capture.finish()
        RecorderState.set(RecorderStatus.Idle)
        RecorderTileService.refresh(this)
        val app = application as TotumApplication
        app.container.applicationScope.launch {
            val saved = app.container.operations.tracking(
                SAVE_KEY,
                OperationKind.TASK,
                getString(R.string.recorder_saving),
            ) {
                val bytes = withContext(Dispatchers.IO) { settle(current, wrote) }
                if (bytes > 0) {
                    notifications.saved(current.file.uri, current.file.name, tookMs, bytes)
                    OperationEnd.Done(1)
                } else {
                    notifications.empty()
                    OperationEnd.Nothing
                }
            }
            Diag.log(TAG, "dewidebug recorder save ended -> $saved")
            if (live == null) leave("the recording is saved or dropped")
        }
    }

    private fun settle(current: Live, wrote: Boolean): Long {
        if (!wrote) {
            current.file.discard("the encoder produced nothing")
            return 0
        }
        val bytes = current.file.publish()
        if (bytes <= 0) current.file.discard("the file is empty")
        return bytes
    }

    private fun fail(why: String, failure: Throwable?, file: RecordingFile?, projection: MediaProjection?) {
        Diag.warn(TAG, "dewidebug recorder could not start: $why", failure)
        file?.discard("start failed: $why")
        runCatching { projection?.stop() }
        live = null
        SystemTaps(this).restore()
        notifications.failed(why)
        RecorderState.set(RecorderStatus.Idle)
        leave("start failed")
    }

    private fun leave(why: String) {
        Diag.log(TAG, "dewidebug recorder service leaving: $why")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(this, permission) ==
        PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "recorder"
        private const val SAVE_KEY = "recorder-save"
        private const val ACTION_START = "com.dewijones92.totum.recorder.START"
        private const val ACTION_STOP = "com.dewijones92.totum.recorder.STOP"
        private const val EXTRA_RESULT = "totum.recorder.result"
        private const val EXTRA_PROJECTION = "totum.recorder.projection"
        private const val EXTRA_MIC = "totum.recorder.mic"
        private const val EXTRA_FACE = "totum.recorder.face"
        private const val EXTRA_TAPS = "totum.recorder.taps"
        private const val EXTRA_WHY = "totum.recorder.why"

        internal fun stopIntent(context: Context, why: String): Intent =
            Intent(context, RecorderService::class.java).setAction(ACTION_STOP).putExtra(EXTRA_WHY, why)

        fun start(context: Context, resultCode: Int, projection: Intent, choices: RecorderChoices) {
            val intent = Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT, resultCode)
                .putExtra(EXTRA_PROJECTION, projection)
                .putExtra(EXTRA_MIC, choices.mic)
                .putExtra(EXTRA_FACE, choices.face)
                .putExtra(EXTRA_TAPS, choices.taps)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context, why: String) {
            Diag.log(TAG, "dewidebug recorder stop asked for: $why")
            context.startService(stopIntent(context, why))
        }
    }
}
