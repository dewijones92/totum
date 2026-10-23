package com.dewijones92.totum.backup

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.Backup
import com.dewijones92.totum.data.backup.BackupGroup
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.backup.BackupPlaylist
import com.dewijones92.totum.data.backup.BackupProgress
import com.dewijones92.totum.data.backup.BackupSubscription
import com.dewijones92.totum.data.group.SourceGroupStore
import com.dewijones92.totum.data.history.PlayHistoryStore
import com.dewijones92.totum.data.playlist.LocalPlaylistStore
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.data.queue.QueueStore
import com.dewijones92.totum.data.subscription.SubscriptionStore
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.PlayState
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.Subscription
import com.dewijones92.totum.domain.canonicalItemId
import com.dewijones92.totum.domain.persisted
import com.dewijones92.totum.domain.playHandleFrom
import com.dewijones92.totum.playback.Chosen
import com.dewijones92.totum.playback.PlaybackProgressStore
import com.dewijones92.totum.stars.STARRED_PLAYLIST
import com.dewijones92.totum.stars.STARRED_PLAYLIST_NAME
import kotlinx.coroutines.flow.Flow
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
    private val extras: Extras = Extras(),
) {
    data class Extras(
        val groups: SourceGroupStore? = null,
        val history: PlayHistoryStore? = null,
    )

    /** Reads and writes the settings a backup carries, without knowing what they mean. */
    interface BackupSettings {
        fun export(): Map<String, String>
        fun restore(values: Map<String, String>)
    }

    fun changes(): List<Pair<String, Flow<*>>> = listOfNotNull(
        "subscriptions" to subscriptions.observeSubscriptions(),
        "playlists" to playlists.observePlaylists(),
        "progress" to progress.observeStates(),
        extras.groups?.let { "groups" to it.observeGroups() },
        extras.history?.let { "history" to it.observe() },
    )

    suspend fun create(includeQueue: Boolean = true): Backup = Backup(
        createdAtEpochMs = now(),
        appVersion = appVersion,
        subscriptions = subscriptions.observeSubscriptions().first().map { it.toBackup() },
        playlists = playlists.observePlaylists().first().map { playlist ->
            BackupPlaylist(playlist.name, playlists.observeItems(playlist.id).first().map { it.toBackup() })
        },
        history = extras.history?.observe()?.first().orEmpty().map { it.toBackup() },
        queue = if (includeQueue) queueStore.load().entries.map { it.item.toBackup() } else emptyList(),
        progress = progress.observeStates().first().mapNotNull { (id, state) -> state.toBackup(id) },
        settings = settings.export(),
        groups = extras.groups?.observeGroups()?.first().orEmpty().map { group ->
            BackupGroup(group.name, group.sources.map { it.toBackup(subscribedAtEpochMs = null) })
        },
    ).also { Diag.log("backup", "created: ${it.summary()}") }

    /**
     * Applies [backup]. Additive: nothing already on the device is removed, because a
     * restore that quietly replaced a library would be unforgivable with the wrong file.
     * The queue is the exception — an order cannot be merged — and is replaced only when
     * the backup has one.
     */
    private suspend fun restorePlaylists(backup: Backup): Int {
        val existing = playlists.observePlaylists().first().associate { it.name to it.id }
        var created = 0
        backup.playlists.forEach { playlist ->
            val id = when {
                playlist.name == STARRED_PLAYLIST_NAME -> STARRED_PLAYLIST.also { playlists.ensure(it, playlist.name) }
                else -> existing[playlist.name] ?: playlists.create(playlist.name).also { created++ }
            }
            playlist.items.mapNotNull { it.toPlayable() }.forEach { playlists.addItem(id, it) }
            Diag.log("backup", "playlist \"${playlist.name}\": ${playlist.items.size} item(s) merged into ${id.value}")
        }
        return created
    }

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

        val restoredPlaylists = restorePlaylists(backup)

        backup.progress.forEach { entry ->
            val id = MediaItemId(canonicalItemId(entry.itemId))
            if (entry.completedAtEpochMs != null) {
                progress.setPlayed(id, played = true)
            } else {
                // A restore REINSTATES a recorded position rather than reporting a live one, so
                // the floor must not judge it. Without this a rewind to the start survives being
                // backed up and is dropped on the way in. Completed entries went to setPlayed above.
                progress.save(id, entry.positionMs, entry.durationMs, Chosen.AS_A_RECORD)
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
            groupMembers = restoreGroups(backup.groups),
            history = restoreHistory(backup.history),
        ).also { Diag.log("backup", "restored: $it") }
    }

    private suspend fun restoreGroups(groups: List<BackupGroup>): Int {
        val store = extras.groups ?: return 0
        var added = 0
        groups.forEach { group ->
            val existing = store.observeGroups().first().firstOrNull { it.name == group.name }
            val id = existing?.id ?: store.create(group.name)
            val members = existing?.sources.orEmpty().mapTo(mutableSetOf()) { it.id }
            group.sources.mapNotNull { it.toSource() }.filterNot { it.id in members }.forEach { source ->
                store.toggleMember(id, source)
                added++
            }
        }
        return added
    }

    private suspend fun restoreHistory(items: List<BackupItem>): Int {
        val store = extras.history ?: return 0
        val known = store.observe().first().mapTo(mutableSetOf()) { it.item.id }
        val missing = items.mapNotNull { it.toPlayable() }.filterNot { it.item.id in known }
        missing.asReversed().forEach { store.record(it) }
        return missing.size
    }

    data class RestoreSummary(
        val subscriptions: Int,
        val playlists: Int,
        val progressEntries: Int,
        val queueEntries: Int,
        val groupMembers: Int = 0,
        val history: Int = 0,
    )
}

private fun Backup.summary() =
    "${subscriptions.size} subs, ${playlists.size} playlists, ${queue.size} queued, ${progress.size} progress, " +
        "${groups.size} groups, ${history.size} history"

private fun Subscription.toBackup() = source.toBackup(subscribedAt.toEpochMilli())

private fun MediaSource.toBackup(subscribedAtEpochMs: Long?) = BackupSubscription(
    id = id.value,
    title = title,
    url = when (this) {
        is MediaSource.PodcastFeed -> feedUrl.value
        is MediaSource.VideoChannel -> channelUrl.value
    },
    kind = when (this) {
        is MediaSource.PodcastFeed -> "podcast"
        is MediaSource.VideoChannel -> "channel"
    },
    subscribedAtEpochMs = subscribedAtEpochMs,
    publisher = (this as? MediaSource.PodcastFeed)?.publisher,
)

private fun PlayState.toBackup(id: MediaItemId): BackupProgress? = when (this) {
    PlayState.Unplayed -> null
    is PlayState.InProgress -> BackupProgress(id.value, positionMs, durationMs)
    PlayState.Played -> BackupProgress(id.value, positionMs = 0, completedAtEpochMs = 1)
}

internal fun PlayableItem.toBackup(): BackupItem {
    val (playbackType, handle) = this.handle.persisted()
    return BackupItem(
        itemId = item.id.value,
        sourceId = item.sourceId.value,
        title = item.title,
        author = item.author,
        publisher = item.publisher,
        thumbnailUrl = item.thumbnailUrl?.value,
        mediaUrl = item.mediaUrl?.value,
        contentKind = item.contentKind.name,
        playbackType = playbackType,
        handle = handle,
        durationMs = item.duration?.inWholeMilliseconds,
        sourceUrl = item.sourceUrl?.value,
        membersOnly = item.membersOnly,
        albumId = item.album?.id,
        albumTitle = item.album?.title,
    )
}

/** Null when the stored handle cannot be rebuilt — a restored row that plays nothing is worse than none. */
internal fun BackupItem.toPlayable(): PlayableItem? {
    val playback = playHandleFrom(playbackType, handle) ?: return null
    return PlayableItem(
        MediaItem(
            id = MediaItemId(canonicalItemId(itemId)),
            sourceId = SourceId(sourceId),
            title = title,
            publishedAt = null,
            duration = durationMs?.milliseconds,
            author = author,
            publisher = publisher,
            thumbnailUrl = thumbnailUrl?.let(HttpUrl::parse),
            mediaUrl = mediaUrl?.let(HttpUrl::parse),
            membersOnly = membersOnly,
            sourceUrl = sourceUrl?.let(HttpUrl::parse),
            contentKind = runCatching { MediaContentKind.valueOf(contentKind) }
                .getOrDefault(MediaContentKind.STANDARD),
            album = albumTitle?.let { AlbumRef(albumId, it) },
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
        MediaSource.PodcastFeed(SourceId(id), title, parsed, websiteUrl = null, publisher = publisher)
    }
}
