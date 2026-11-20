package com.dewijones92.totum.phonemedia

import android.content.ContentUris
import android.content.Context
import android.media.ThumbnailUtils
import android.util.Size
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.pxOrElse
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import java.io.File

class PhoneThumbnailFetcher(
    private val context: Context,
    private val kind: PhoneMediaKind,
    private val key: String,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val width = options.size.width.pxOrElse { DEFAULT_PX }.coerceIn(MIN_PX, MAX_PX)
        val height = options.size.height.pxOrElse { DEFAULT_PX }.coerceIn(MIN_PX, MAX_PX)
        val size = Size(width, height)
        val ref = PhoneMediaIds.refOf(key)
        val bitmap = runCatching {
            if (ref.startsWith(PhoneMediaIds.GOOGLE_REF_PREFIX)) {
                val file = File(PhoneMediaIds.pathOf(key) ?: error("no path in $key"))
                when (kind) {
                    PhoneMediaKind.VIDEO -> ThumbnailUtils.createVideoThumbnail(file, size, null)
                    else -> ThumbnailUtils.createImageThumbnail(file, size, null)
                }
            } else {
                context.contentResolver.loadThumbnail(
                    ContentUris.withAppendedId(MediaStorePhoneSource.collectionOf(kind), ref.toLong()),
                    size,
                    null,
                )
            }
        }.onFailure {
            Diag.log(
                "phone-media",
                "no thumbnail for $kind/$ref at ${width}x$height: ${it.javaClass.simpleName} ${it.message}",
            )
        }.getOrNull() ?: return null
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val (kind, key) = PhoneMediaIds.parseThumbnail(data.toString()) ?: return null
            return PhoneThumbnailFetcher(context, kind, key, options)
        }
    }

    private companion object {
        const val DEFAULT_PX = 320
        const val MIN_PX = 64
        const val MAX_PX = 1024
    }
}
