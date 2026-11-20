package com.dewijones92.totum.data.phone

import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind

public object MediaStoreColumns {
    public const val ID: String = "_id"
    public const val DATA: String = "_data"
    public const val TITLE: String = "title"
    public const val DISPLAY_NAME: String = "_display_name"
    public const val DURATION: String = "duration"
    public const val DATE_ADDED: String = "date_added"
    public const val DATE_TAKEN: String = "datetaken"
    public const val MIME_TYPE: String = "mime_type"
    public const val SIZE: String = "_size"
    public const val WIDTH: String = "width"
    public const val HEIGHT: String = "height"
    public const val BUCKET: String = "bucket_display_name"
    public const val ARTIST: String = "artist"
    public const val ALBUM: String = "album"
    public const val IS_MUSIC: String = "is_music"
    public const val IS_AUDIOBOOK: String = "is_audiobook"
    public const val IS_RINGTONE: String = "is_ringtone"
    public const val IS_NOTIFICATION: String = "is_notification"
    public const val IS_ALARM: String = "is_alarm"

    public fun projection(kind: PhoneMediaKind): List<String> = when (kind) {
        PhoneMediaKind.VIDEO -> listOf(
            ID, DATA, TITLE, DISPLAY_NAME, DURATION, DATE_ADDED, DATE_TAKEN, MIME_TYPE, SIZE, WIDTH, HEIGHT, BUCKET,
            ARTIST, ALBUM,
        )
        PhoneMediaKind.AUDIO -> listOf(
            ID, DATA, TITLE, DISPLAY_NAME, DURATION, DATE_ADDED, MIME_TYPE, SIZE, BUCKET, ARTIST, ALBUM, IS_MUSIC,
            IS_AUDIOBOOK,
        )
        PhoneMediaKind.PHOTO -> listOf(
            ID, DATA, TITLE, DISPLAY_NAME, DATE_ADDED, DATE_TAKEN, MIME_TYPE, SIZE, WIDTH, HEIGHT, BUCKET,
        )
    }
}

public data class MediaStorePlan(
    val selection: String?,
    val args: List<String>,
    val orderBy: String,
    val limit: Int,
    val offset: Int,
)

public fun PhoneMediaQuery.toPlan(): MediaStorePlan {
    val clauses = mutableListOf<String>()
    val args = mutableListOf<String>()
    if (kind == PhoneMediaKind.AUDIO) {
        clauses += "${MediaStoreColumns.IS_RINGTONE} = 0"
        clauses += "${MediaStoreColumns.IS_NOTIFICATION} = 0"
        clauses += "${MediaStoreColumns.IS_ALARM} = 0"
    }
    folder?.takeIf { it.isNotBlank() }?.let {
        clauses += "${MediaStoreColumns.BUCKET} = ?"
        args += it
    }
    val words = search.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    words.forEach { word ->
        val like = "%${word.escapedForLike()}%"
        val columns = buildList {
            add(MediaStoreColumns.TITLE)
            add(MediaStoreColumns.DISPLAY_NAME)
            if (kind == PhoneMediaKind.AUDIO) {
                add(MediaStoreColumns.ARTIST)
                add(MediaStoreColumns.ALBUM)
            }
        }
        clauses += columns.joinToString(" OR ", "(", ")") { "$it LIKE ? ESCAPE '\\'" }
        repeat(columns.size) { args += like }
    }
    return MediaStorePlan(
        selection = clauses.takeIf { it.isNotEmpty() }?.joinToString(" AND "),
        args = args,
        orderBy = orderBy(),
        limit = limit,
        offset = offset,
    )
}

private fun PhoneMediaQuery.orderBy(): String {
    val newest = when (kind) {
        PhoneMediaKind.AUDIO -> "${MediaStoreColumns.DATE_ADDED} DESC, ${MediaStoreColumns.ID} DESC"
        else ->
            "${MediaStoreColumns.DATE_TAKEN} DESC, ${MediaStoreColumns.DATE_ADDED} DESC, ${MediaStoreColumns.ID} DESC"
    }
    val oldest = when (kind) {
        PhoneMediaKind.AUDIO -> "${MediaStoreColumns.DATE_ADDED} ASC, ${MediaStoreColumns.ID} ASC"
        else -> "${MediaStoreColumns.DATE_TAKEN} ASC, ${MediaStoreColumns.DATE_ADDED} ASC, ${MediaStoreColumns.ID} ASC"
    }
    return when (sort) {
        PhoneSort.NEWEST -> newest
        PhoneSort.OLDEST -> oldest
        PhoneSort.TITLE -> "${MediaStoreColumns.TITLE} ASC, ${MediaStoreColumns.ID} ASC"
        PhoneSort.LONGEST -> if (kind == PhoneMediaKind.PHOTO) {
            newest
        } else {
            "${MediaStoreColumns.DURATION} DESC, ${MediaStoreColumns.ID} DESC"
        }
        PhoneSort.LARGEST -> "${MediaStoreColumns.SIZE} DESC, ${MediaStoreColumns.ID} DESC"
    }
}

private fun String.escapedForLike(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

public data class PhoneMediaRow(
    val id: Long,
    val data: String?,
    val title: String?,
    val displayName: String?,
    val durationMs: Long? = null,
    val dateAddedSeconds: Long? = null,
    val dateTakenMs: Long? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bucket: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val isMusic: Boolean = false,
    val isAudiobook: Boolean = false,
)

public fun PhoneMediaRow.toEntry(kind: PhoneMediaKind, contentUri: String): PhoneMediaEntry? {
    val path = data?.takeIf { it.isNotBlank() } ?: return null
    val name = displayName?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
    val shownTitle = title?.takeIf { it.isNotBlank() && it != name } ?: name.substringBeforeLast('.', name)
    return PhoneMediaEntry(
        kind = kind,
        key = PhoneMediaIds.keyOf(id.toString(), path),
        title = shownTitle,
        path = path,
        uri = contentUri,
        mimeType = mimeType,
        durationMs = durationMs?.takeIf { it > 0 },
        addedAtSeconds = dateAddedSeconds,
        takenAtMs = dateTakenMs?.takeIf { it > 0 },
        sizeBytes = sizeBytes,
        artist = artist?.takeUnless { it.isUnknownTag() },
        album = album?.takeUnless { it.isUnknownTag() },
        width = width?.takeIf { it > 0 },
        height = height?.takeIf { it > 0 },
        folder = bucket?.takeIf { it.isNotBlank() },
        contentKind = when {
            kind != PhoneMediaKind.AUDIO -> MediaContentKind.STANDARD
            isAudiobook -> MediaContentKind.AUDIOBOOK
            isMusic -> MediaContentKind.MUSIC
            else -> MediaContentKind.STANDARD
        },
    )
}

private fun String.isUnknownTag(): Boolean = isBlank() || this == "<unknown>"
