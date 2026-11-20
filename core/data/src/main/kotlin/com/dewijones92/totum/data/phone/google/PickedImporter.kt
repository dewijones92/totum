package com.dewijones92.totum.data.phone.google

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.phone.ImportedKeys
import com.dewijones92.totum.data.phone.ImportedMedia
import com.dewijones92.totum.data.phone.ImportedPhoneMedia
import com.dewijones92.totum.domain.PhoneMediaKind
import java.io.File

public class PickedImporter(
    private val api: PhotosPickerApi,
    private val imports: ImportedPhoneMedia,
    private val clock: () -> Long,
) {
    public suspend fun readAll(sessionId: String, onPage: (Int) -> Unit): PickerCall<List<PickedItem>> {
        val all = mutableListOf<PickedItem>()
        var token: String? = null
        do {
            when (val page = api.items(sessionId, token)) {
                is PickerCall.Ok -> {
                    all += page.value.mediaItems
                    token = page.value.nextPageToken
                    onPage(all.size)
                }
                is PickerCall.No -> return page
            }
        } while (token != null)
        return PickerCall.Ok(all)
    }

    public suspend fun importAll(
        items: List<PickedItem>,
        onItem: (index: Int, total: Int, name: String) -> Unit,
    ): PickerState.Finished {
        var imported = 0
        var already = 0
        val failed = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            onItem(index, items.size, nameOf(item))
            when (val result = importOne(item)) {
                ImportResult.Imported -> imported++
                ImportResult.AlreadyHad -> already++
                is ImportResult.Failed -> failed += result.line
            }
        }
        return PickerState.Finished(imported, already, failed)
    }

    private suspend fun importOne(item: PickedItem): ImportResult {
        val key = ImportedKeys.keyOf(item.id)
        val name = nameOf(item)
        if (imports.has(key)) {
            Diag.log("google-photos", "$name was imported before; skipped")
            return ImportResult.AlreadyHad
        }
        val file = File(imports.directory(), "$key.${extensionOf(item)}")
        return when (val got = api.download(item, file)) {
            is PickerCall.Ok -> {
                imports.add(importedOf(item, key, file, got.value, clock()))
                ImportResult.Imported
            }
            is PickerCall.No -> {
                Diag.warn("google-photos", "$name was not imported: ${got.reason}")
                ImportResult.Failed("$name: ${got.reason}")
            }
        }
    }
}

private sealed interface ImportResult {
    data object Imported : ImportResult

    data object AlreadyHad : ImportResult

    data class Failed(val line: String) : ImportResult
}

private const val ID_SHOWN = 8
private const val MAX_EXTENSION = 5

private fun nameOf(item: PickedItem): String =
    item.mediaFile?.filename ?: "${item.id.take(ID_SHOWN)}.${if (item.isVideo) "mp4" else "jpg"}"

private fun importedOf(item: PickedItem, key: String, file: File, bytes: Long, now: Long): ImportedMedia {
    val kind = if (item.isVideo) PhoneMediaKind.VIDEO else PhoneMediaKind.PHOTO
    val name = nameOf(item)
    return ImportedMedia(
        key = key,
        kind = kind.key,
        title = name.substringBeforeLast('.', name),
        fileName = file.name,
        mimeType = item.mediaFile?.mimeType,
        createdAtMs = PickerDurations.expiryMs(item.createTime),
        importedAtMs = now,
        sizeBytes = bytes,
        width = item.mediaFile?.mediaFileMetadata?.width,
        height = item.mediaFile?.mediaFileMetadata?.height,
    )
}

private fun extensionOf(item: PickedItem): String {
    val fromName = item.mediaFile?.filename?.substringAfterLast('.', "")?.lowercase()
        ?.takeIf { it.length in 2..MAX_EXTENSION }
    if (fromName != null) return fromName
    val fromMime = item.mediaFile?.mimeType?.substringAfter('/', "")?.lowercase()
    return when {
        item.isVideo -> "mp4"
        fromMime == "jpeg" || fromMime.isNullOrEmpty() -> "jpg"
        else -> fromMime
    }
}
