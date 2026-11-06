package com.dewijones92.totum.recorder

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Handler
import android.view.WindowManager
import com.dewijones92.totum.common.Diag
import java.io.FileDescriptor

internal class ScreenRecording private constructor(
    private val projection: MediaProjection,
    private val recorder: MediaRecorder,
    private val display: VirtualDisplay,
    private val callback: MediaProjection.Callback,
    val size: CaptureSize,
) {
    fun finish(): Boolean {
        val wrote = runCatching { recorder.stop() }
            .onFailure { Diag.warn(TAG, "dewidebug recorder encoder stop failed; the file may be empty", it) }
            .isSuccess
        runCatching { recorder.release() }
        runCatching { display.release() }
        runCatching { projection.unregisterCallback(callback) }
        runCatching { projection.stop() }
        Diag.log(TAG, "dewidebug recorder capture released wroteVideo=$wrote")
        return wrote
    }

    companion object {
        private const val TAG = "recorder"
        private const val FRAME_RATE = 30
        private const val VIDEO_BITS_PER_SECOND = 8_000_000
        private const val AUDIO_BITS_PER_SECOND = 128_000
        private const val AUDIO_SAMPLE_RATE = 44_100

        @Suppress("LongParameterList", "TooGenericExceptionCaught")
        fun begin(
            context: Context,
            projection: MediaProjection,
            output: FileDescriptor,
            withMic: Boolean,
            handler: Handler,
            onProjectionStopped: () -> Unit,
        ): ScreenRecording {
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Diag.log(TAG, "dewidebug recorder the system ended the screen capture")
                    onProjectionStopped()
                }
            }
            projection.registerCallback(callback, handler)
            val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
            val size = captureSize(bounds.width(), bounds.height())
            val density = context.resources.displayMetrics.densityDpi
            val recorder = MediaRecorder(context)
            try {
                if (withMic) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoSize(size.width, size.height)
                recorder.setVideoFrameRate(FRAME_RATE)
                recorder.setVideoEncodingBitRate(VIDEO_BITS_PER_SECOND)
                if (withMic) {
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    recorder.setAudioEncodingBitRate(AUDIO_BITS_PER_SECOND)
                    recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                }
                recorder.setOutputFile(output)
                recorder.prepare()
                val display = checkNotNull(
                    projection.createVirtualDisplay(
                        "totum-recorder",
                        size.width,
                        size.height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        recorder.surface,
                        null,
                        handler,
                    ),
                ) { "Android would not make a display to capture" }
                recorder.start()
                Diag.log(
                    TAG,
                    "dewidebug recorder encoder=H264 ${size.width}x${size.height} dpi=$density fps=$FRAME_RATE " +
                        "videoBitrate=$VIDEO_BITS_PER_SECOND mic=$withMic " +
                        "audio=${if (withMic) "AAC ${AUDIO_SAMPLE_RATE}Hz $AUDIO_BITS_PER_SECOND" else "none"} " +
                        "screen=${bounds.width()}x${bounds.height()}",
                )
                return ScreenRecording(projection, recorder, display, callback, size)
            } catch (failure: Exception) {
                runCatching { recorder.release() }
                runCatching { projection.unregisterCallback(callback) }
                throw failure
            }
        }
    }
}
