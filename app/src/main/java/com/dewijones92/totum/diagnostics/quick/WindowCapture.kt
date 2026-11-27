package com.dewijones92.totum.diagnostics.quick

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.core.graphics.createBitmap
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.diagnostics.EncodedScreenshot
import com.dewijones92.totum.diagnostics.encodeCapturedBitmap
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal suspend fun captureWindow(view: View, index: Int = 0): EncodedScreenshot? {
    val window = view.context.findActivity()?.window
    val decor = window?.decorView
    if (window == null || decor == null || !decor.isDrawn()) {
        Diag.warn(TAG, "dewidebug screenshot not taken: no drawn window to copy (window=${window != null})")
        return null
    }
    val bitmap = createBitmap(decor.width, decor.height)
    val result = withTimeoutOrNull(COPY_TIMEOUT_MS) {
        suspendCancellableCoroutine { next ->
            runCatching {
                PixelCopy.request(window, bitmap, { code -> if (next.isActive) next.resume(code) }, mainHandler)
            }.onFailure { failure ->
                Diag.warn(TAG, "dewidebug screenshot not taken: the window copy was refused", failure)
                if (next.isActive) next.resume(FAILED_TO_ASK)
            }
        }
    }
    if (result != PixelCopy.SUCCESS) {
        val answer = result ?: "nothing within $COPY_TIMEOUT_MS ms"
        Diag.warn(TAG, "dewidebug screenshot not taken: PixelCopy answered $answer")
        return null
    }
    Diag.log(TAG, "dewidebug window copied for the report: ${bitmap.width}x${bitmap.height}")
    return encodeCapturedBitmap(bitmap, index)
}

private fun View.isDrawn(): Boolean = width > 0 && height > 0

private val mainHandler = Handler(Looper.getMainLooper())
private const val TAG = "diagnostics"
private const val COPY_TIMEOUT_MS = 3_000L
private const val FAILED_TO_ASK = -1
