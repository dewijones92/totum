package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl

/**
 * How to start playing something. A video keeps its **watch URL** rather than a
 * stream URL, because streaming URLs expire — it is re-resolved at play time.
 * Anything already on disk carries its path.
 */
public sealed interface PlayHandle {
    public data class Video(public val watchUrl: HttpUrl) : PlayHandle
    public data class LocalVideo(public val localPath: String) : PlayHandle
    public data class Podcast(
        public val localPath: String? = null,
        /**
         * An audio-only version of the same thing, when one exists.
         *
         * A torrent is ONE file carrying both tracks, so listening to one otherwise pulls the
         * video down too — measured 15.2 MB/min against 2.1 for the audio alone. The home
         * server can remux the audio out and serve it as HLS; this is where that URL lives, so
         * "play this without the video" needs no knowledge of torrents anywhere above.
         *
         * Null for a podcast enclosure, which is already audio and has nothing to strip.
         */
        public val audioUrl: HttpUrl? = null,
    ) : PlayHandle

    /**
     * What to call this handle in a log line.
     *
     * A literal per case rather than `javaClass.simpleName`, because R8 renames the classes and
     * a **release** build printed `handle=wr3`. Report 0.1.383 has it on every route line, and it
     * reads perfectly in debug — which is why it shipped. The field says which route an item
     * took, so it is one of the few worth having at all; an unreadable one is worse than none.
     *
     * Exhaustive on purpose: a new handle cannot be added without giving it a name.
     */
    public val label: String
        get() = when (this) {
            is Video -> "Video"
            is LocalVideo -> "LocalVideo"
            is Podcast -> if (localPath != null) "PodcastFile" else "Podcast"
        }

    /**
     * This handle plus any route [newer] knows about that this one does not.
     *
     * Re-queueing something can only ever ADD a way to reach it, never take one away. That
     * direction is the whole point: the naive "newer wins" loses a `localPath` when a fresh
     * copy arrives without one, and the app then streams a file already sitting on the disk.
     *
     * Needed because the currently-playing entry is deliberately left in place when its item is
     * queued again — moving it would interrupt playback — so without this it keeps the route it
     * was created with forever. That is how a torrent kept playing as video after the audio-only
     * URL existed: the fresh handle carrying it was discarded as a duplicate.
     *
     * Different pillars never merge; a video is not a fuller version of a podcast.
     */
    public fun mergedWith(newer: PlayHandle): PlayHandle = when {
        this !is Podcast || newer !is Podcast -> newer
        else -> Podcast(
            localPath = newer.localPath ?: localPath,
            audioUrl = newer.audioUrl ?: audioUrl,
        )
    }

    /**
     * Which pillar this came from. Mixed lists (queue, history, playlists) label their
     * rows from this rather than sniffing a URL — the handle already knows, exactly.
     */
    public val pillar: MediaKind
        get() = when (this) {
            is Video, is LocalVideo -> MediaKind.VIDEO
            is Podcast -> MediaKind.PODCAST
        }
}

/**
 * A [MediaItem] plus how to play it — the one shape used wherever the app stores
 * or queues something playable: the up-next queue, local playlists, and play
 * history. Pillar-agnostic: which variant the [handle] is decides how playback
 * starts, and nothing above this needs to know.
 */
public data class PlayableItem(
    public val item: MediaItem,
    public val handle: PlayHandle,
) {
    /**
     * Where a download's bytes come from, or null if there is nothing to fetch yet.
     *
     * A video's stable **watch** URL wins over [MediaItem.mediaUrl]: that field holds a
     * resolved stream, which expires, and is absent entirely for anything queued from
     * search. The engine re-resolves from the watch URL, so this is the only reliable
     * answer — and having it in one place is what stops callers inventing their own.
     */
    public val fetchUrl: HttpUrl?
        get() = (handle as? PlayHandle.Video)?.watchUrl ?: item.mediaUrl
}

/**
 * Which pillar a raw feed [MediaItem] belongs to, inferred from its media URL. Only for
 * items that do **not** yet have a [PlayHandle] — anything holding one reads
 * [PlayHandle.pillar] instead, which knows exactly rather than guessing.
 *
 * This is the single place the guess lives. It used to live in two, with rules that
 * quietly disagreed: the playable mapping matched only `youtube.com/watch`, while the
 * download router matched any YouTube host. A Shorts URL therefore downloaded through
 * the video engine but was queued as if it were a podcast enclosure.
 */
public val MediaItem.pillar: MediaKind
    get() {
        val url = mediaUrl?.value ?: return MediaKind.PODCAST
        return if (STREAMING_HOSTS.any { it in url }) MediaKind.VIDEO else MediaKind.PODCAST
    }

private val STREAMING_HOSTS = listOf("youtube.com", "youtu.be")

/**
 * A feed item as something playable/saveable — a video keeps its watch URL as the
 * handle, a podcast its enclosure. Null when the item has no media URL yet, so there is
 * nothing to play.
 */
public fun MediaItem.toPlayableOrNull(): PlayableItem? {
    val url = mediaUrl ?: return null
    return PlayableItem(this, if (pillar == MediaKind.VIDEO) PlayHandle.Video(url) else PlayHandle.Podcast())
}

/**
 * As [toPlayableOrNull] but total. Downloads use this: an item with no URL still has to
 * become a row so the failure is recorded against something that names it, rather than
 * being dropped silently.
 */
public fun MediaItem.asPlayable(): PlayableItem = toPlayableOrNull() ?: PlayableItem(this, PlayHandle.Podcast())

/**
 * How a [PlayHandle] is written down, and read back.
 *
 * Here rather than in the database module because four tables and the backup file all
 * store the same two columns, and the vocabulary ("VIDEO", "PODCAST", "LOCAL_VIDEO") has
 * to mean the same thing in every one of them. A second copy would drift silently: a
 * backup written with one spelling and read with another restores a queue that plays
 * nothing.
 */
public fun PlayHandle.persisted(): Pair<String, String?> = when (this) {
    is PlayHandle.Video -> PERSISTED_VIDEO to watchUrl.value
    is PlayHandle.LocalVideo -> PERSISTED_LOCAL_VIDEO to localPath
    // A Podcast handle now carries TWO things, and the schema has one column for them. They
    // are encoded into it rather than migrated, because the alternative is a database change
    // across four tables for a field only torrents use — and because an old row, which is a
    // bare path, still reads correctly below.
    is PlayHandle.Podcast -> PERSISTED_PODCAST to listOfNotNull(
        localPath?.let { "$PATH_FIELD$it" },
        audioUrl?.let { "$AUDIO_FIELD${it.value}" },
    ).joinToString(FIELD_SEPARATOR).takeIf { it.isNotEmpty() }
}

/** The inverse of [persisted]; null when the stored pair cannot make a usable handle. */
public fun playHandleFrom(type: String, handle: String?): PlayHandle? = when (type) {
    PERSISTED_VIDEO -> handle?.let(HttpUrl::parse)?.let(PlayHandle::Video)
    PERSISTED_LOCAL_VIDEO -> handle?.let(PlayHandle::LocalVideo)
    // Legacy rows are a bare local path with no field prefix, so anything without one is read
    // exactly as it always was. Losing this would empty the localPath of every already-queued
    // download and re-fetch the lot.
    else -> {
        val fields = handle?.split(FIELD_SEPARATOR).orEmpty()
        PlayHandle.Podcast(
            localPath = fields.firstOrNull { it.startsWith(PATH_FIELD) }?.removePrefix(PATH_FIELD)
                ?: handle?.takeIf { fields.none { f -> f.contains(FIELD_MARK) } },
            audioUrl = fields.firstOrNull { it.startsWith(AUDIO_FIELD) }
                ?.removePrefix(AUDIO_FIELD)?.let(HttpUrl::parse),
        )
    }
}

/**
 * Field encoding for a Podcast handle inside one column.
 *
 * A newline separates and a `name=` prefix labels, because neither a filesystem path nor a URL
 * can contain a newline — so nothing has to be escaped and an old bare path is unambiguous.
 */
private const val FIELD_SEPARATOR = "\n"
private const val FIELD_MARK = "="
private const val PATH_FIELD = "path="
private const val AUDIO_FIELD = "audio="

private const val PERSISTED_VIDEO = "VIDEO"
private const val PERSISTED_LOCAL_VIDEO = "LOCAL_VIDEO"
private const val PERSISTED_PODCAST = "PODCAST"
