package com.dewijones92.totum.recorder

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.dewijones92.totum.common.Diag
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class CaptureSize(val width: Int, val height: Int)

internal fun captureSize(screenWidth: Int, screenHeight: Int, longSideCap: Int = LONG_SIDE_CAP): CaptureSize {
    val longSide = maxOf(screenWidth, screenHeight)
    val scale = if (longSide > longSideCap) longSideCap.toDouble() / longSide else 1.0
    return CaptureSize(evenOf(screenWidth * scale), evenOf(screenHeight * scale))
}

private fun evenOf(value: Double): Int = maxOf(2, (value.toInt() / 2) * 2)

internal fun recordingName(now: LocalDateTime): String = "Totum-${NAME_FORMAT.format(now)}.mp4"

internal class RecordingFile private constructor(
    val uri: Uri,
    val name: String,
    val descriptor: ParcelFileDescriptor,
    private val resolver: ContentResolver,
) {
    fun publish(): Long {
        val size = runCatching { descriptor.statSize }.getOrDefault(0L)
        runCatching { descriptor.close() }
        resolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null,
            null,
        )
        Diag.log(TAG, "dewidebug recorder file published uri=$uri name=$name bytes=$size")
        return size
    }

    fun discard(why: String) {
        runCatching { descriptor.close() }
        val removed = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
        Diag.log(TAG, "dewidebug recorder file discarded name=$name rowsRemoved=$removed why=$why")
    }

    companion object {
        private const val TAG = "recorder"
        const val FOLDER = "Movies/Totum"

        fun create(context: Context, now: LocalDateTime = LocalDateTime.now()): RecordingFile {
            val resolver = context.contentResolver
            val name = recordingName(now)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Totum")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = checkNotNull(
                resolver.insert(collection, values)
            ) { "the gallery would not make a file for $name" }
            val descriptor = runCatching {
                checkNotNull(
                    resolver.openFileDescriptor(uri, "w")
                ) { "the gallery would not open $name" }
            }
                .onFailure { resolver.delete(uri, null, null) }
                .getOrThrow()
            Diag.log(TAG, "dewidebug recorder file created uri=$uri name=$name folder=$FOLDER")
            return RecordingFile(uri, name, descriptor, resolver)
        }
    }
}

private const val LONG_SIDE_CAP = 1920
private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss")
