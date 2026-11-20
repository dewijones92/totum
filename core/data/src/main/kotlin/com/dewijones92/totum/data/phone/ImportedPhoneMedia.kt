package com.dewijones92.totum.data.phone

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.PhoneMediaEntry
import com.dewijones92.totum.domain.PhoneMediaIds
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
public data class ImportedMedia(
    val key: String,
    val kind: String,
    val title: String,
    val fileName: String,
    val mimeType: String? = null,
    val createdAtMs: Long? = null,
    val importedAtMs: Long = 0,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
) {
    public val mediaKind: PhoneMediaKind get() = PhoneMediaKind.entries.first { it.key == kind }
}

public object ImportedKeys {
    public fun keyOf(googleId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(googleId.toByteArray())
        return PhoneMediaIds.GOOGLE_REF_PREFIX + digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
    }

    private const val KEY_BYTES = 8
}

public class ImportedPhoneMedia(private val dir: File) : PhoneMediaSource {

    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(dir, INDEX)
    private val lock = Any()
    private var items: List<ImportedMedia> = load()

    public fun fileOf(key: String): File? = synchronized(lock) {
        items.firstOrNull { it.key == key }?.let { File(dir, it.fileName) }?.takeIf { it.isFile }
    }

    public fun has(key: String): Boolean = synchronized(lock) { items.any { it.key == key } && fileOf(key) != null }

    public fun directory(): File = dir.also { it.mkdirs() }

    public fun count(): Int = synchronized(lock) { items.size }

    public fun add(item: ImportedMedia) {
        synchronized(lock) {
            items = items.filterNot { it.key == item.key } + item
            save()
        }
        Diag.log(
            "phone-media",
            "imported ${item.kind}/${item.key} (${item.fileName}, ${item.sizeBytes} bytes) " +
                "into the Google Photos library",
        )
    }

    public fun remove(key: String): Boolean = synchronized(lock) {
        val gone = items.firstOrNull { it.key == key } ?: return false
        File(dir, gone.fileName).delete()
        items = items.filterNot { it.key == key }
        save()
        Diag.log("phone-media", "removed ${gone.kind}/${gone.key} (${gone.fileName}) from the Google Photos library")
        true
    }

    override suspend fun page(query: PhoneMediaQuery): PhoneMediaPage {
        val snapshot = synchronized(lock) { items }
        return query.applyTo(snapshot.filter { File(dir, it.fileName).isFile }.map(::entryOf))
    }

    override suspend fun folders(kind: PhoneMediaKind): List<PhoneFolder> {
        val count = synchronized(lock) { items.count { it.mediaKind == kind && File(dir, it.fileName).isFile } }
        return if (count > 0) listOf(PhoneFolder(FOLDER, count)) else emptyList()
    }

    private fun entryOf(item: ImportedMedia): PhoneMediaEntry {
        val file = File(dir, item.fileName)
        return PhoneMediaEntry(
            kind = item.mediaKind,
            key = PhoneMediaIds.keyOf(item.key, file.absolutePath),
            title = item.title,
            path = file.absolutePath,
            uri = file.toURI().toString(),
            mimeType = item.mimeType,
            takenAtMs = item.createdAtMs,
            addedAtSeconds = item.importedAtMs / MS_PER_SECOND,
            sizeBytes = item.sizeBytes,
            width = item.width,
            height = item.height,
            folder = FOLDER,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun load(): List<ImportedMedia> = try {
        if (index.isFile) json.decodeFromString<List<ImportedMedia>>(index.readText()) else emptyList()
    } catch (failure: Exception) {
        Diag.warn("phone-media", "the Google Photos library index could not be read; starting empty", failure)
        emptyList()
    }

    private fun save() {
        dir.mkdirs()
        val partial = File(dir, "$INDEX.part")
        partial.writeText(json.encodeToString(items))
        check(partial.renameTo(index)) { "could not replace the Google Photos library index" }
    }

    public companion object {
        public const val FOLDER: String = "Google Photos"
        private const val INDEX = "index.json"
        private const val MS_PER_SECOND = 1000L
    }
}
