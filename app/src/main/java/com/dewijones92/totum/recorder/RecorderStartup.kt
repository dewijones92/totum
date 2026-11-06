package com.dewijones92.totum.recorder

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

internal sealed interface OrphanVerdict {
    data object Publish : OrphanVerdict

    data class Delete(val why: String) : OrphanVerdict
}

internal fun orphanVerdict(bytes: Long, durationMs: Long?): OrphanVerdict = when {
    bytes <= 0 -> OrphanVerdict.Delete("the file is empty")
    durationMs == null || durationMs <= 0 -> OrphanVerdict.Delete("$bytes bytes but no playable video in them")
    else -> OrphanVerdict.Publish
}

internal object RecorderDiagnostics {
    @Volatile
    var lastResult: String = "none yet"

    @Volatile
    var lastCleanup: String = "none yet"

    fun values(context: Context): Map<String, String> {
        val choices = RecorderChoiceStore(context).read()
        val taps = SystemTaps(context)
        return mapOf(
            "recorder.status" to RecorderState.status.value.toString(),
            "recorder.choices" to "voice=${choices.mic} face=${choices.face} taps=${choices.taps}",
            "recorder.systemTapsGranted" to taps.permitted().toString(),
            "recorder.systemTapsLeftOn" to taps.leftOn().toString(),
            "recorder.lastResult" to lastResult,
            "recorder.lastCleanup" to lastCleanup,
        )
    }
}

internal fun cleanUpAfterAKilledRecording(context: Context, scope: CoroutineScope) {
    if (RecorderState.recording) {
        Diag.log(TAG, "dewidebug recorder start-up clean-up skipped: a recording is running")
        return
    }
    SystemTaps(context).restoreIfLeftOn("app start")
    scope.launch(Dispatchers.IO) { OrphanedRecordings(context).settle() }
}

internal class OrphanedRecordings(
    private val context: Context,
    private val now: () -> Instant = Instant::now,
) {
    private val resolver: ContentResolver = context.contentResolver

    private class Orphan(val uri: Uri, val name: String, val addedSeconds: Long)

    fun settle() {
        val cutoff = now().epochSecond - GRACE_SECONDS
        val orphans = runCatching { find(cutoff) }
            .onFailure { Diag.warn(TAG, "dewidebug recorder could not look for unfinished recordings", it) }
            .getOrDefault(emptyList())
        var published = 0
        var deleted = 0
        orphans.forEach { orphan ->
            if (RecorderState.recording) {
                Diag.log(TAG, "dewidebug recorder unfinished ${orphan.name} left alone: a recording just started")
                return@forEach
            }
            if (settle(orphan)) published++ else deleted++
        }
        val summary = "found ${orphans.size} unfinished, published $published, deleted $deleted at ${now()}"
        RecorderDiagnostics.lastCleanup = summary
        Diag.log(TAG, "dewidebug recorder start-up clean-up of unfinished recordings: $summary")
    }

    private fun settle(orphan: Orphan): Boolean {
        val bytes = runCatching { resolver.openFileDescriptor(orphan.uri, "r")?.use { it.statSize } }.getOrNull() ?: 0L
        val duration = durationOf(orphan.uri)
        val verdict = orphanVerdict(bytes, duration)
        Diag.log(
            TAG,
            "dewidebug recorder unfinished ${orphan.name} (added at epoch ${orphan.addedSeconds}s) bytes=$bytes " +
                "durationMs=$duration -> $verdict",
        )
        return when (verdict) {
            OrphanVerdict.Publish -> {
                val rows = resolver.update(
                    orphan.uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                Diag.log(TAG, "dewidebug recorder recovered ${orphan.name} into the gallery rowsUpdated=$rows")
                true
            }
            is OrphanVerdict.Delete -> {
                val rows = runCatching { resolver.delete(orphan.uri, null, null) }.getOrDefault(0)
                Diag.log(TAG, "dewidebug recorder deleted unfinished ${orphan.name}: ${verdict.why} rowsRemoved=$rows")
                false
            }
        }
    }

    private fun durationOf(uri: Uri): Long? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        }
    }.getOrNull()

    private fun find(cutoffSeconds: Long): List<Orphan> {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 1 " +
                    "AND ${MediaStore.MediaColumns.DATE_ADDED} < ? AND " +
                    "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf("${RecordingFile.FOLDER}/%", cutoffSeconds.toString(), context.packageName),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_ADDED,
        )
        return buildList {
            resolver.query(collection, projection, args, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    add(
                        Orphan(
                            uri = ContentUris.withAppendedId(collection, cursor.getLong(0)),
                            name = cursor.getString(1).orEmpty(),
                            addedSeconds = cursor.getLong(2),
                        ),
                    )
                }
            }
        }
    }

    private companion object {
        const val GRACE_SECONDS = 60L
    }
}

private const val TAG = "recorder"
