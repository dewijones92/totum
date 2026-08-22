package com.dewijones92.totum.data.backup

import kotlinx.serialization.Serializable

/**
 * Everything about this app that isn't re-downloadable, as one JSON file.
 *
 * **Decisions, stated because they are commitments rather than details:**
 *
 * *Records, not media.* Downloaded audio and video are deliberately excluded. They are
 * re-fetchable by definition, and including them turns a backup you can email into
 * gigabytes you cannot. What cannot be recovered is the *choices* — what you subscribed
 * to, what you queued, what you already listened to — so that is what this holds.
 *
 * *Versioned, and refuses the future.* A file written by a newer build is rejected rather
 * than half-read: silently skipping fields it does not recognise would restore an
 * incomplete library and call it success. Older files are fine — an absent section simply
 * restores nothing.
 *
 * *Additive on restore, except the queue.* Restoring never deletes what is already on the
 * device, because a restore that quietly replaced a library would be unforgivable if it
 * were the wrong file. The queue is the exception: it is an ordered position, not a set,
 * and merging two queues produces an order neither one asked for — so a backup's queue
 * replaces the current one, and only when it has one.
 */
@Serializable
public data class Backup(
    val version: Int = CURRENT_VERSION,
    /** When it was taken, for the user's benefit rather than ours. */
    val createdAtEpochMs: Long = 0,
    val appVersion: String = "",
    val subscriptions: List<BackupSubscription> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    val history: List<BackupItem> = emptyList(),
    val queue: List<BackupItem> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
) {
    public companion object {
        /**
         * Bumped only when an older build could not read the file correctly. Adding an
         * optional section does not need a bump: an old build ignores it, and a new build
         * reading an old file sees the default.
         */
        public const val CURRENT_VERSION: Int = 1
    }
}

/** A subscribed feed or channel. The URL is what actually re-creates it. */
@Serializable
public data class BackupSubscription(
    val id: String,
    val title: String,
    val url: String,
    /** "podcast" or "channel" — which pillar it belongs to. */
    val kind: String,
    val subscribedAtEpochMs: Long? = null,
)

@Serializable
public data class BackupPlaylist(
    val name: String,
    val items: List<BackupItem> = emptyList(),
)

/**
 * One playable thing, flattened. The same shape as the app's denormalized rows, so a
 * restored queue entry or playlist item is indistinguishable from one that never left.
 */
@Serializable
public data class BackupItem(
    val itemId: String,
    val sourceId: String,
    val title: String,
    val author: String? = null,
    val thumbnailUrl: String? = null,
    val mediaUrl: String? = null,
    val contentKind: String,
    val playbackType: String,
    val handle: String? = null,
    /**
     * What the listing said, kept because nothing can reconstruct it from a restored row.
     *
     * Optional and defaulted, which this format explicitly allows without a version bump: an older
     * build ignores them and a newer build reading an older file sees the defaults, which is exactly
     * what a backup taken before this contained.
     */
    val durationMs: Long? = null,
    val sourceUrl: String? = null,
    val membersOnly: Boolean = false,
)

/** How far through something you got, or that you finished it. */
@Serializable
public data class BackupProgress(
    val itemId: String,
    val positionMs: Long,
    val durationMs: Long? = null,
    val completedAtEpochMs: Long? = null,
)
