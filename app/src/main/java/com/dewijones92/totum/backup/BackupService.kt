package com.dewijones92.totum.backup

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.BackupPlaylist
import com.dewijones92.totum.data.backup.BackupProgress
import com.dewijones92.totum.data.backup.BackupSubscription
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.QueueStore
import com.dewijones92.totum.data.subscription.SubscriptionStore
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.domain.persisted
import com.dewijones92.totum.domain.playHandleFrom
import com.dewijones92.totum.playback.PlaybackProgressStore
import kotlinx.coroutines.flow.first
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds

/**
 * Gathers everything worth keeping into a [Backup], and puts it back.
 *
 * Lives in `:app` rather than `:core:data` because a backup spans every store plus the
 * preferences, and the only place that knows about all of them is the graph that wires
 * them. The *format* is in `:core:data` so it can be tested without any of this.
 *
 * **YouTube channel subscriptions are not in the file.** They live in the signed-in
 * account, not on the device, so they come back by signing in — putting a stale copy in a
 * backup would let it disagree with the account, and the account is the truth.
 */
class BackupService(
    private val subscriptions: SubscriptionStore,
    private val playlists: LocalPlaylistStore,
    private val queueStore: QueueStore,
    private val progress: PlaybackProgressStore,
    private val settings: BackupSettings,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Reads and writes the settings a backup carries, without knowing what they mean. */
    interface BackupSettings {
        fun export(): Map<String, String>
        fun restore(values: Map<String, String>)
    }

    suspend fun create(): Backup = Backup(
        createdAtEpochMs = now(),
        appVersion = appVersion,
        subscriptions = subscriptions.observeSubscriptions().first().map { it.toBackup() },
        playlists = playlists.observePlaylists().first().map { playlist ->
            BackupPlaylist(playlist.name, playlists.observeItems(playlist.id).first().map { it.toBackup() })
        },
        history = emptyList(),
        queue = queueStore.load().entries.map { it.item.toBackup() },
        progress = progress.observeStates().first().mapNotNull { (id, state) -> state.toBackup(id) },
        settings = settings.export(),
    ).also { Diag.log("backup", "created: ${it.summary()}") }

    /**
     * Applies [backup]. Additive: nothing already on the device is removed, because a
     * restore that quietly replaced a library would be unforgivable with the wrong file.
     * The queue is the exception — an order cannot be merged — and is replaced only when
     * the backup has one.
     */
    suspend fun restore(backup: Backup): RestoreSummary {
        var restoredSubscriptions = 0
        backup.subscriptions.forEach { entry ->
            val source = entry.toSource() ?: return@forEach
            if (subscriptions.contains(source.id)) return@forEach
            // No items: they arrive on the next refresh. Re-fetching every feed here would
            // make a restore slow and able to fail halfway.
            subscriptions.saveSource(
                Subscription(source, Instant.ofEpochMilli(entry.subscribedAtEpochMs ?: now())),
                items = emptyList(),
            )
            restoredSubscriptions++
        }

        val existingNames = playlists.observePlaylists().first().mapTo(mutableSetOf()) { it.name }
        var restoredPlaylists = 0
        backup.playlists.forEach { playlist ->
            if (playlist.name in existingNames) return@forEach
            val id = playlists.create(playlist.name)
            playlist.items.mapNotNull { it.toPlayable() }.forEach { playlists.addItem(id, it) }
            restoredPlaylists++
        }

        backup.progress.forEach { entry ->
            val id = MediaItemId(entry.itemId)
            if (entry.completedAtEpochMs != null) {
                progress.setPlayed(id, played = true)
            } else {
                progress.save(id, entry.positionMs, entry.durationMs)
            }
        }

        // Written straight to the store, so the in-memory PlaybackQueue — which hydrates
        // once at construction — does not see it until the next launch. The restore
        // message says so rather than leaving an apparently empty queue unexplained.
        if (backup.queue.isNotEmpty()) {
            val entries = backup.queue.mapNotNull { it.toPlayable() }.map { QueueEntry(it) }
            queueStore.save(QueueSnapshot(entries))
        }

        if (backup.settings.isNotEmpty()) settings.restore(backup.settings)

        return RestoreSummary(
            subscriptions = restoredSubscriptions,
            playlists = restoredPlaylists,
            progressEntries = backup.progress.size,
            queueEntries = backup.queue.size,
        ).also { Diag.log("backup", "restored: $it") }
    }

    data class RestoreSummary(
        val subscriptions: Int,
        val playlists: Int,
        val progressEntries: Int,
        val queueEntries: Int,
    )
}

private fun Backup.summary() =
    "${subscriptions.size} subs, ${playlists.size} playlists, ${queue.size} queued, ${progress.size} progress"

private fun Subscription.toBackup() = BackupSubscription(
    id = source.id.value,
    title = source.title,
    url = when (val s = source) {
        is MediaSource.PodcastFeed -> s.feedUrl.value
        is MediaSource.VideoChannel -> s.channelUrl.value
    },
    kind = when (source) {
        is MediaSource.PodcastFeed -> "podcast"
        is MediaSource.VideoChannel -> "channel"
    },
    subscribedAtEpochMs = subscribedAt.toEpochMilli(),
)

private fun PlayState.toBackup(id: MediaItemId): BackupProgress? = when (this) {
    PlayState.Unplayed -> null
    is PlayState.InProgress -> BackupProgress(id.value, positionMs, durationMs)
    PlayState.Played -> BackupProgress(id.value, positionMs = 0, completedAtEpochMs = 1)
}

private fun PlayableItem.toBackup(): BackupItem {
    val (playbackType, handle) = this.handle.persisted()
    return BackupItem(
        itemId = item.id.value,
        sourceId = item.sourceId.value,
        title = item.title,
        author = item.author,
        thumbnailUrl = item.thumbnailUrl?.value,
        mediaUrl = item.mediaUrl?.value,
        contentKind = item.contentKind.name,
        playbackType = playbackType,
        handle = handle,
        durationMs = item.duration?.inWholeMilliseconds,
        sourceUrl = item.sourceUrl?.value,
        membersOnly = item.membersOnly,
    )
}

/** Null when the stored handle cannot be rebuilt — a restored row that plays nothing is worse than none. */
private fun BackupItem.toPlayable(): PlayableItem? {
    val playback = playHandleFrom(playbackType, handle) ?: return null
    return PlayableItem(
        MediaItem(
            id = MediaItemId(itemId),
            sourceId = SourceId(sourceId),
            title = title,
            publishedAt = null,
            duration = durationMs?.milliseconds,
            author = author,
            thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
            mediaUrl = mediaUrl?.let(HttpUrl::parse),
            membersOnly = membersOnly,
            sourceUrl = sourceUrl?.let(HttpUrl::parse),
            contentKind = runCatching { MediaContentKind.valueOf(contentKind) }
                .getOrDefault(MediaContentKind.STANDARD),
        ),
        playback,
    )
}

/** Null for a URL that no longer parses, rather than a subscription that can never load. */
private fun BackupSubscription.toSource(): MediaSource? {
    val parsed = HttpUrl.parse(url) ?: return null
    return if (kind == "channel") {
        MediaSource.VideoChannel(SourceId(id), title, parsed)
    } else {
        MediaSource.PodcastFeed(SourceId(id), title, parsed, websiteUrl = null)
    }
}
