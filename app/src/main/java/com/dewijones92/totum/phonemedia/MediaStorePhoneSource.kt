package com.dewijones92.totum.phonemedia

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.MediaStoreColumns
import com.dewijones92.totum.data.phone.PhoneFolder
import com.dewijones92.totum.data.phone.PhoneMediaPage
import com.dewijones92.totum.data.phone.PhoneMediaQuery
import com.dewijones92.totum.data.phone.PhoneMediaRow
import com.dewijones92.totum.data.phone.PhoneMediaSource
import com.dewijones92.totum.data.phone.toEntry
import com.dewijones92.totum.data.phone.toPlan
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStorePhoneSource(context: Context) : PhoneMediaSource {

    private val resolver: ContentResolver = context.contentResolver

    override suspend fun page(query: PhoneMediaQuery): PhoneMediaPage = withContext(Dispatchers.IO) {
        val plan = query.toPlan()
        val collection = collectionOf(query.kind)
        val args = Bundle().apply {
            plan.selection?.let { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it) }
            if (plan.args.isNotEmpty()) {
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, plan.args.toTypedArray())
            }
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, plan.orderBy)
            putInt(ContentResolver.QUERY_ARG_LIMIT, plan.limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, plan.offset)
        }
        val started = System.currentTimeMillis()
        val rows = mutableListOf<PhoneMediaRow>()
        resolver.query(collection, MediaStoreColumns.projection(query.kind).toTypedArray(), args, null)?.use { cursor ->
            while (cursor.moveToNext()) rows += cursor.toRow()
        } ?: Diag.warn("phone-media", "the media store gave no cursor for ${query.kind}")
        val entries = rows.mapNotNull { row ->
            row.toEntry(query.kind, ContentUris.withAppendedId(collection, row.id).toString())
        }
        Diag.log(
            "phone-media",
            "queried ${query.kind} offset=${plan.offset} limit=${plan.limit} where=${plan.selection} " +
                "order=${plan.orderBy}: ${rows.size} rows, ${entries.size} with a file path, " +
                "${System.currentTimeMillis() - started}ms",
        )
        PhoneMediaPage(entries, query.nextOffset(rows.size))
    }

    override suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder> = withContext(Dispatchers.IO) {
        val counts = linkedMapOf<String, Int>()
        val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStoreColumns.BUCKET) }
        resolver.query(collectionOf(kind), arrayOf(MediaStoreColumns.BUCKET), args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                counts[name] = (counts[name] ?: 0) + 1
            }
        }
        Diag.log("phone-media", "folders of $kind: ${counts.size} (${counts.values.sum()} files)")
        counts.map { PhoneFolder(it.key, it.value) }.sortedByDescending { it.count }
    }

    companion object {
        fun collectionOf(kind: PhoneMediaKind): Uri = when (kind) {
            PhoneMediaKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            PhoneMediaKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            PhoneMediaKind.PHOTO -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        }
    }
}

private fun Cursor.string(name: String): String? =
    getColumnIndex(name).takeIf { it >= 0 }?.let { if (isNull(it)) null else getString(it) }

private fun Cursor.long(name: String): Long? =
    getColumnIndex(name).takeIf { it >= 0 }?.let { if (isNull(it)) null else getLong(it) }

private fun Cursor.toRow() = PhoneMediaRow(
    id = long(MediaStoreColumns.ID) ?: 0L,
    data = string(MediaStoreColumns.DATA),
    title = string(MediaStoreColumns.TITLE),
    displayName = string(MediaStoreColumns.DISPLAY_NAME),
    durationMs = long(MediaStoreColumns.DURATION),
    dateAddedSeconds = long(MediaStoreColumns.DATE_ADDED),
    dateTakenMs = long(MediaStoreColumns.DATE_TAKEN),
    mimeType = string(MediaStoreColumns.MIME_TYPE),
    sizeBytes = long(MediaStoreColumns.SIZE),
    width = long(MediaStoreColumns.WIDTH)?.toInt(),
    height = long(MediaStoreColumns.HEIGHT)?.toInt(),
    bucket = string(MediaStoreColumns.BUCKET),
    artist = string(MediaStoreColumns.ARTIST),
    album = string(MediaStoreColumns.ALBUM),
    isMusic = long(MediaStoreColumns.IS_MUSIC) == 1L,
    isAudiobook = long(MediaStoreColumns.IS_AUDIOBOOK) == 1L,
)
