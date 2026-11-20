package com.dewijones92.totum.support

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

data class PhoneFixture(val uri: Uri, val path: String, val name: String)

object PhoneMediaFixtures {

    const val FOLDER = "totum-phone-media-test"
    const val SCALE_FOLDER = "totum-phone-media-test-scale"

    fun grantMediaPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val target = instrumentation.targetContext.packageName
        listOf("READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO", "READ_MEDIA_IMAGES").forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("pm grant $target android.permission.$permission"),
            ).use { it.readBytes() }
        }
    }

    fun video(context: Context, name: String): PhoneFixture {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("clip.mp4").use { it.readBytes() }
        return insert(context, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "Movies/$FOLDER", name, "video/mp4", bytes)
    }

    fun song(context: Context, name: String, hertz: Int = 440): PhoneFixture =
        insert(
            context,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            "Music/$FOLDER",
            name,
            "audio/x-wav",
            toneWav(hertz, 6)
        )

    fun photo(
        context: Context,
        name: String,
        colour: Int,
        folder: String = FOLDER,
        width: Int = 640,
        height: Int = 360,
    ): PhoneFixture =
        insert(
            context,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "Pictures/$folder",
            name,
            "image/jpeg",
            jpeg(colour, name, width, height)
        )

    fun manyPhotos(context: Context, count: Int): Int {
        val tiny = jpeg(Color.DKGRAY, "scale", width = 64, height = 64)
        val existing = countIn(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/$SCALE_FOLDER/")
        (existing until count).forEach { n ->
            insert(
                context,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "Pictures/$SCALE_FOLDER",
                "scale-%04d.jpg".format(n),
                "image/jpeg",
                tiny,
                reuse = false,
            )
        }
        return countIn(context, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "Pictures/$SCALE_FOLDER/")
    }

    private fun countIn(context: Context, collection: Uri, relativePath: String): Int =
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { it.count } ?: 0

    @Suppress("LongParameterList")
    private fun insert(
        context: Context,
        collection: Uri,
        relativePath: String,
        name: String,
        mime: String,
        bytes: ByteArray,
        reuse: Boolean = true,
    ): PhoneFixture {
        val resolver = context.contentResolver
        if (reuse) {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA),
                "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(name, "$relativePath/"),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                    return PhoneFixture(uri, cursor.getString(1), name)
                }
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(collection, values)) { "the media store refused $name" }
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)!!.use {
            it.moveToFirst()
            it.getString(0)
        }
        return PhoneFixture(uri, path, name)
    }

    private fun jpeg(colour: Int, label: String, width: Int = 640, height: Int = 360): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colour)
        val paint = Paint().apply {
            color = Color.WHITE
            textSize = height / 8f
            isAntiAlias = true
        }
        canvas.drawText(label, height / 16f, height / 2f, paint)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private fun toneWav(hertz: Int, seconds: Int): ByteArray {
        val rate = 16_000
        val samples = rate * seconds
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(
            samples
        ) { n -> data.putShort((sin(2 * PI * hertz * n / rate) * Short.MAX_VALUE * 0.4).toInt().toShort()) }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
            putInt(36 + samples * 2)
            put("WAVEfmt ".toByteArray())
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(rate)
            putInt(rate * 2)
            putShort(2)
            putShort(16)
            put("data".toByteArray())
            putInt(samples * 2)
        }
        return header.array() + data.array()
    }
}
