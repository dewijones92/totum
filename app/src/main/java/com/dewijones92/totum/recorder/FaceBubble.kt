package com.dewijones92.totum.recorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.dewijones92.totum.common.Diag
import kotlin.math.abs
import kotlin.math.max

internal class FaceBubble(private val context: Context, private val onLost: (String) -> Unit) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val executor = ContextCompat.getMainExecutor(context)
    private var root: DraggableCircle? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    fun show(): Boolean {
        if (root != null) return true
        val frontId = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_FRONT
        }
        if (frontId == null) {
            Diag.log(TAG, "dewidebug recorder face bubble not shown: this phone reports no front camera")
            return false
        }
        val density = context.resources.displayMetrics.density
        val outer = (BUBBLE_DP * density).toInt()
        val rim = (RIM_DP * density).toInt()
        val bounds = windows.maximumWindowMetrics.bounds
        val texture = TextureView(context)
        val circle = DraggableCircle(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setPadding(rim, rim, rim, rim)
        }
        texture.outlineProvider = ovalOutline
        texture.clipToOutline = true
        circle.addView(texture, FrameLayout.LayoutParams(outer - 2 * rim, outer - 2 * rim))
        val params = WindowManager.LayoutParams(
            outer,
            outer,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.width() - outer - (MARGIN_DP * density).toInt()
            y = (bounds.height() * START_HEIGHT_FRACTION).toInt()
        }
        circle.onMoved = { dx, dy ->
            params.x += dx
            params.y += dy
            windows.updateViewLayout(circle, params)
        }
        windows.addView(circle, params)
        root = circle
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                open(frontId, st, texture, outer - 2 * rim)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        Diag.log(TAG, "dewidebug recorder face bubble shown camera=$frontId sizePx=$outer at=${params.x},${params.y}")
        return true
    }

    private fun lose(why: String, detail: String) {
        if (root == null) return
        Diag.log(TAG, "dewidebug recorder face bubble lost: $detail; removing it and saying so")
        hide()
        onLost(why)
    }

    fun hide() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        session = null
        device = null
        root?.let { view -> runCatching { windows.removeView(view) } }
        if (root != null) Diag.log(TAG, "dewidebug recorder face bubble removed and camera closed")
        root = null
    }

    private fun open(id: String, texture: SurfaceTexture, view: TextureView, sidePx: Int) {
        val permitted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!permitted) {
            lose("the camera permission is gone", "no camera permission")
            return
        }
        val characteristics = cameras.getCameraCharacteristics(id)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java).orEmpty().sortedBy { it.width * it.height }
        val chosen = sizes.firstOrNull { minOf(it.width, it.height) >= MIN_PREVIEW_SIDE } ?: sizes.lastOrNull()
        if (chosen == null) {
            lose("the front camera offers no picture size", "the front camera offers no preview size")
            return
        }
        texture.setDefaultBufferSize(chosen.width, chosen.height)
        val sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = (sensor + displayDegrees()) % FULL_TURN
        view.setTransform(coverMatrix(chosen.width, chosen.height, sidePx, rotation))
        Diag.log(
            TAG,
            "dewidebug recorder face bubble preview=${chosen.width}x${chosen.height} sensorOrientation=$sensor " +
                "displayDegrees=${displayDegrees()} rotation=$rotation mirrored=true",
        )
        runCatching {
            cameras.openCamera(
                id,
                executor,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        if (root == null) return camera.close()
                        device = camera
                        runCatching { preview(camera, Surface(texture)) }
                            .onFailure { lose("the camera preview could not start", "preview failed: $it") }
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        lose("the camera was taken by another app", "camera disconnected (another app took it?)")
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        lose("the camera failed (error $error)", "camera error=$error")
                    }
                },
            )
        }.onFailure { lose("the camera could not be opened", "openCamera threw: $it") }
    }

    private fun preview(camera: CameraDevice, surface: Surface) {
        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(surface) }
        camera.createCaptureSession(
            SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(surface)),
                executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (root == null) return configured.close()
                        session = configured
                        configured.setRepeatingRequest(request.build(), null, null)
                        Diag.log(TAG, "dewidebug recorder face bubble camera preview running")
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        lose("the camera preview could not start", "camera session could not be configured")
                    }
                },
            ),
        )
    }

    private fun displayDegrees(): Int = when (displays.getDisplay(Display.DEFAULT_DISPLAY)?.rotation) {
        Surface.ROTATION_90 -> QUARTER_TURN
        Surface.ROTATION_180 -> HALF_TURN
        Surface.ROTATION_270 -> THREE_QUARTER_TURN
        else -> 0
    }

    private class DraggableCircle(context: Context) : FrameLayout(context) {
        var onMoved: (Int, Int) -> Unit = { _, _ -> }
        private var lastX = 0f
        private var lastY = 0f
        private var travelled = 0f

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    travelled = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    lastX = event.rawX
                    lastY = event.rawY
                    travelled += abs(dx) + abs(dy)
                    onMoved(dx.toInt(), dy.toInt())
                }
                MotionEvent.ACTION_UP -> if (travelled < TAP_SLOP_PX) performClick()
            }
            return true
        }

        override fun performClick(): Boolean = super.performClick()
    }

    private companion object {
        const val TAG = "recorder"
        const val BUBBLE_DP = 120
        const val RIM_DP = 3
        const val MARGIN_DP = 12
        const val START_HEIGHT_FRACTION = 0.6
        const val MIN_PREVIEW_SIDE = 480
        const val QUARTER_TURN = 90
        const val HALF_TURN = 180
        const val THREE_QUARTER_TURN = 270
        const val FULL_TURN = 360
        const val TAP_SLOP_PX = 12f

        val ovalOutline = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }

        fun coverMatrix(bufferWidth: Int, bufferHeight: Int, sidePx: Int, rotation: Int): Matrix {
            val centre = sidePx / 2f
            val sideways = rotation == QUARTER_TURN || rotation == THREE_QUARTER_TURN
            val shownWidth = if (sideways) bufferHeight else bufferWidth
            val shownHeight = if (sideways) bufferWidth else bufferHeight
            val cover = max(sidePx.toFloat() / shownWidth, sidePx.toFloat() / shownHeight)
            return Matrix().apply {
                setScale(bufferWidth.toFloat() / sidePx, bufferHeight.toFloat() / sidePx, centre, centre)
                postRotate(rotation.toFloat(), centre, centre)
                postScale(cover, cover, centre, centre)
                postScale(-1f, 1f, centre, centre)
            }
        }
    }
}
