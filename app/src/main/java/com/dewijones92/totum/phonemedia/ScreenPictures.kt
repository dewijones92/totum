package com.dewijones92.totum.phonemedia

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PhoneMediaEntry
import java.io.File
import java.io.IOException
import java.security.MessageDigest

fun interface ScreenPictures {
    fun forScreen(entry: PhoneMediaEntry): File?

    companion object {
        val AsTheyAre: ScreenPictures = ScreenPictures { entry ->
            File(entry.path).takeIf { it.isFile }
        }
    }
}

class AndroidScreenPictures(private val context: Context) : ScreenPictures {

    private val dir = File(context.cacheDir, "screen-pictures")

    override fun forScreen(entry: PhoneMediaEntry): File? {
        dir.mkdirs()
        val target = File(dir, "${digestOf(entry.key)}.jpg")
        if (target.length() > 0) {
            Diag.log("phone-media", "dewidebug screen picture for ${entry.id.value}: reused ${target.name}")
            return target
        }
        val started = System.currentTimeMillis()
        return try {
            val source = ImageDecoder.createSource(context.contentResolver, entry.uri.toUri())
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val (width, height) = fitWithin(info.size.width, info.size.height, SCREEN_LONG_EDGE)
                decoder.setTargetSize(width, height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            Diag.log(
                "phone-media",
                "dewidebug screen picture for ${entry.id.value}: ${bitmap.width}x${bitmap.height}, " +
                    "${target.length()} bytes in ${System.currentTimeMillis() - started}ms",
            )
            bitmap.recycle()
            trim()
            target
        } catch (failure: IOException) {
            target.delete()
            Diag.warn("phone-media", "dewidebug screen picture for ${entry.id.value} failed: ${failure.message}")
            null
        }
    }

    private fun trim() {
        val old = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(KEPT)
        old.forEach { it.delete() }
        if (old.isNotEmpty()) Diag.log("phone-media", "dewidebug screen pictures: ${old.size} old ones removed")
    }

    private fun digestOf(key: String): String =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).take(DIGEST_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val SCREEN_LONG_EDGE = 1920
        const val JPEG_QUALITY = 88
        const val KEPT = 32
        const val DIGEST_BYTES = 10
    }
}

internal fun fitWithin(width: Int, height: Int, longEdge: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= longEdge || width <= 0 || height <= 0) return width to height
    val scale = longEdge.toDouble() / longest
    return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
}
