package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.backup.toPlayable
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SkipSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Suppress("TooManyFunctions")
public class JsonDownloadStore(private val file: File, private val now: () -> Instant = Instant::now) : DownloadStore {

    @Serializable
    private data class Row(
        val item: BackupItem,
        val state: String,
        val audioOnly: Boolean,
        val bytes: Long = 0,
        val totalBytes: Long? = null,
        val path: String? = null,
        val reason: String? = null,
        val downloadedAtMs: Long? = null,
        val openedAtMs: Long? = null,
    )

    private val lock = Mutex()
    private val rows = MutableStateFlow(load())

    override suspend fun learnFacts(resolved: MediaItem): Unit = Unit

    override fun observeAll(): Flow<Map<MediaItemId, DownloadState>> =
        rows.map { all -> all.mapNotNull { row -> row.playable()?.let { it.item.id to row.state() } }.toMap() }

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> = rows.map { all ->
        all.mapNotNull { row ->
            val playable = row.playable() ?: return@mapNotNull null
            val done = row.state() as? DownloadState.Downloaded ?: return@mapNotNull null
            DownloadedMedia(
                playable,
                done.localPath,
                done.audioOnly,
                downloadedAt = row.downloadedAtMs?.let(Instant::ofEpochMilli),
                openedAt = row.openedAtMs?.let(Instant::ofEpochMilli),
            )
        }
    }

    override fun observeRecords(): Flow<List<DownloadRecord>> =
        rows.map { all -> all.mapNotNull { row -> row.playable()?.let { DownloadRecord(it, row.state()) } } }

    override suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean): Unit = change { all ->
        val earlier = all.firstOrNull { it.item.itemId == item.item.id.value }
        val row = Row(
            item.toBackup(),
            state = state.kind(),
            audioOnly = audioOnly,
            bytes = (state as? DownloadState.Downloading)?.downloadedBytes ?: 0,
            totalBytes = (state as? DownloadState.Downloading)?.totalBytes,
            path = (state as? DownloadState.Downloaded)?.localPath,
            reason = (state as? DownloadState.Failed)?.reason,
            downloadedAtMs = if (state is DownloadState.Downloaded) now().toEpochMilli() else earlier?.downloadedAtMs,
            openedAtMs = earlier?.openedAtMs,
        )
        all.filterNot { it.item.itemId == row.item.itemId } + row
    }

    override suspend fun get(id: MediaItemId): DownloadState =
        rows.value.firstOrNull { it.item.itemId == id.value }?.state() ?: DownloadState.NotDownloaded

    override suspend fun request(id: MediaItemId): DownloadRequest? =
        rows.value.firstOrNull { it.item.itemId == id.value }?.let { row ->
            row.playable()?.let { DownloadRequest(it, row.audioOnly) }
        }

    override suspend fun remove(id: MediaItemId): Unit = change { all -> all.filterNot { it.item.itemId == id.value } }

    override suspend fun noteOpened(id: MediaItemId): Boolean {
        var found = false
        change { all ->
            all.map { row ->
                if (row.item.itemId == id.value) {
                    found = true
                    row.copy(openedAtMs = now().toEpochMilli())
                } else {
                    row
                }
            }
        }
        return found
    }

    public suspend fun relocate(id: MediaItemId, path: String): Unit = change { all ->
        all.map { row -> if (row.item.itemId == id.value && row.state == DOWNLOADED) row.copy(path = path) else row }
    }

    override suspend fun rememberSkipSegments(id: MediaItemId, segments: List<SkipSegment>): Unit = Unit

    private suspend fun change(edit: (List<Row>) -> List<Row>) = lock.withLock {
        val next = edit(rows.value)
        rows.value = next
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.new")
        temporary.writeText(JSON.encodeToString(ListSerializer(Row.serializer()), next))
        if (!temporary.renameTo(file)) temporary.copyTo(file, overwrite = true).also { temporary.delete() }
    }

    private fun load(): List<Row> = if (!file.exists()) {
        emptyList()
    } else {
        runCatching { JSON.decodeFromString(ListSerializer(Row.serializer()), file.readText()) }
            .onFailure { Diag.warn("download", "the download list at $file could not be read; starting empty", it) }
            .getOrDefault(emptyList())
    }

    private fun Row.playable(): PlayableItem? = item.toPlayable()

    private fun Row.state(): DownloadState = when (state) {
        DOWNLOADING -> DownloadState.Downloading(bytes, totalBytes?.takeIf { it >= bytes })
        DOWNLOADED -> path?.let { DownloadState.Downloaded(it, audioOnly) } ?: DownloadState.Failed("no file recorded")
        FAILED -> DownloadState.Failed(reason ?: "failed")
        else -> DownloadState.NotDownloaded
    }

    private fun DownloadState.kind(): String = when (this) {
        is DownloadState.Downloading -> DOWNLOADING
        is DownloadState.Downloaded -> DOWNLOADED
        is DownloadState.Failed -> FAILED
        DownloadState.NotDownloaded -> NOT_DOWNLOADED
    }

    private companion object {
        const val DOWNLOADING = "downloading"
        const val DOWNLOADED = "downloaded"
        const val FAILED = "failed"
        const val NOT_DOWNLOADED = "none"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
