package com.dewijones92.totum.database

import com.dewijones92.totum.data.download.DownloadRecord
import com.dewijones92.totum.data.download.DownloadRequest
import com.dewijones92.totum.data.download.DownloadStore
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayableItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Room-backed [DownloadStore]; the only place download entities and domain state meet. */
// The count is DownloadStore's surface plus the small entity<->domain mappers; this is the one
// place those two meet, and scattering them would defeat the point of the class.
@Suppress("TooManyFunctions")
public class RoomDownloadStore(private val dao: DownloadDao) : DownloadStore {

    override fun observeAll(): Flow<Map<MediaItemId, DownloadState>> =
        dao.observeAll().map { rows -> rows.associate { MediaItemId(it.itemId) to it.toState() } }

    override fun observeDownloaded(): Flow<List<DownloadedMedia>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toDownloaded() } }

    override fun observeRecords(): Flow<List<DownloadRecord>> =
        dao.observeAll().map { rows ->
            rows.mapNotNull { row -> playlistItemFrom(row)?.let { DownloadRecord(it, row.toState()) } }
        }

    override suspend fun get(id: MediaItemId): DownloadState =
        dao.get(id.value)?.toState() ?: DownloadState.NotDownloaded

    override suspend fun put(item: PlayableItem, state: DownloadState, audioOnly: Boolean) {
        dao.upsert(state.toEntity(item, audioOnly))
    }

    /**
     * The request behind ANY row, not just a finished one.
     *
     * A retry starts from a failed row and a cancel reports on a running one, and neither is
     * reachable through [observeDownloaded] — which by design only knows about downloads that
     * completed.
     */
    override suspend fun request(id: MediaItemId): DownloadRequest? {
        val row = dao.get(id.value) ?: return null
        return DownloadRequest(playlistItemFrom(row) ?: return null, row.audioOnly)
    }

    override suspend fun remove(id: MediaItemId) {
        dao.delete(id.value)
    }

    private fun DownloadEntity.toState(): DownloadState = when (status) {
        STATUS_DOWNLOADING -> DownloadState.Downloading(downloadedBytes, totalBytes)
        STATUS_DOWNLOADED -> DownloadState.Downloaded(localPath.orEmpty(), audioOnly)
        STATUS_FAILED -> DownloadState.Failed(failureReason.orEmpty())
        else -> DownloadState.NotDownloaded
    }

    /** Null unless the row is a finished download that still describes a playable item. */
    private fun DownloadEntity.toDownloaded(): DownloadedMedia? {
        if (status != STATUS_DOWNLOADED) return null
        val path = localPath?.takeIf { it.isNotEmpty() } ?: return null
        return DownloadedMedia(playlistItemFrom(this) ?: return null, path, audioOnly)
    }

    private fun DownloadState.toEntity(item: PlayableItem, requestedAudioOnly: Boolean): DownloadEntity {
        val (playbackType, handleValue) = item.handle.typeAndHandle()
        val media = item.item
        return DownloadEntity(
            itemId = media.id.value,
            status = statusKey(),
            downloadedBytes = (this as? DownloadState.Downloading)?.downloadedBytes ?: 0,
            totalBytes = (this as? DownloadState.Downloading)?.totalBytes,
            localPath = (this as? DownloadState.Downloaded)?.localPath,
            failureReason = (this as? DownloadState.Failed)?.reason,
            // A finished download states its own variant; anything else records what was ASKED for,
            // which is the only thing a retry can go on. Writing false for a running or failed row
            // meant retrying an audio-only download quietly fetched the whole video.
            audioOnly = (this as? DownloadState.Downloaded)?.audioOnly ?: requestedAudioOnly,
            title = media.title,
            author = media.author,
            thumbnailUrl = media.thumbnailUrl?.value,
            sourceId = media.sourceId.value,
            contentKind = media.contentKind.name,
            // The listing's own facts, or they are lost the moment the row is written -- see
            // PlaylistItemColumns.
            viewsText = media.viewsText,
            publishedText = media.publishedText,
            publishedAtEpochMs = media.publishedAt?.toEpochMilli(),
            durationMs = media.duration?.inWholeMilliseconds,
            sourceUrl = media.sourceUrl?.value,
            membersOnly = media.membersOnly,
            playbackType = playbackType,
            handle = handleValue,
            mediaUrl = media.mediaUrl?.value,
        )
    }

    private fun DownloadState.statusKey(): String = when (this) {
        DownloadState.NotDownloaded -> STATUS_NOT_DOWNLOADED
        is DownloadState.Downloading -> STATUS_DOWNLOADING
        is DownloadState.Downloaded -> STATUS_DOWNLOADED
        is DownloadState.Failed -> STATUS_FAILED
    }

    private companion object {
        const val STATUS_NOT_DOWNLOADED = "not_downloaded"
        const val STATUS_DOWNLOADING = "downloading"
        const val STATUS_DOWNLOADED = "downloaded"
        const val STATUS_FAILED = "failed"
    }
}
