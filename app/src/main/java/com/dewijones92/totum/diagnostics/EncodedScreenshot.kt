package com.dewijones92.totum.diagnostics

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.core.graphics.scale
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class EncodedScreenshot(val name: String, val width: Int, val height: Int, val jpegBytes: Int, val base64: String)

internal fun scaledToFit(width: Int, height: Int, longest: Int = LONGEST_SIDE): Pair<Int, Int> {
    val side = maxOf(width, height)
    if (side <= longest) return width to height
    val scale = longest.toDouble() / side
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

internal fun sampleSizeFor(width: Int, height: Int, longest: Int = LONGEST_SIDE): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= longest) sample *= 2
    return sample
}

suspend fun encodeScreenshot(resolver: ContentResolver, uri: Uri, index: Int): EncodedScreenshot? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return@runCatching null
            val (w, h) = scaledToFit(decoded.width, decoded.height)
            val scaled = if (w == decoded.width) decoded else decoded.scale(w, h)
            val jpeg = jpegOf(scaled, QUALITY).let { if (it.size > TARGET_BYTES) jpegOf(scaled, LOWER_QUALITY) else it }
            Diag.log(
                "diagnostics",
                "screenshot ${index + 1}: ${bounds.outWidth}x${bounds.outHeight} -> ${w}x$h, ${jpeg.size}B"
            )
            EncodedScreenshot(
                "screenshot-${index + 1}.jpg",
                w,
                h,
                jpeg.size,
                Base64.encodeToString(jpeg, Base64.NO_WRAP)
            )
        }.getOrElse { failure ->
            Diag.warn("diagnostics", "screenshot ${index + 1} could not be read", failure)
            null
        }
    }

private fun jpegOf(bitmap: Bitmap, quality: Int): ByteArray =
    ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

const val MAX_SCREENSHOTS = 6
private const val LONGEST_SIDE = 1600
private const val QUALITY = 80
private const val LOWER_QUALITY = 60
private const val TARGET_BYTES = 450_000
