package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl

/**
 * A finished copy of something on this device.
 *
 * [audioOnly] matters at play time, not just for labelling: the queue's automatic downloads
 * fetch audio alone, so a copy of a video may hold no picture.
 */
public data class LocalCopy(
    public val path: String,
    public val audioOnly: Boolean = false,
)

/** Why nothing was played, in the two ways that can happen. */
public enum class Refusal {
    /** No file, no stream URL — there is nothing to play at all, network or not. */
    NothingToPlay,

    /** Playable, but only over a network there isn't one of. */
    NotOnThisDevice,

    /**
     * There is a network and a stream address, and the stream itself will not play — asked for
     * after recovery has exhausted its retries, when the only remaining question is whether
     * anything on the disk can stand in.
     */
    StreamWillNotPlay,
}

/**
 * The one answer to "how do I play this, right now" — for both pillars.
 *
 * It exists because that question was answered in two places that disagreed. The queue's
 * podcast branch asked the download store for a local copy; its video branch did not, so a
 * YouTube item whose audio the auto-downloader had already fetched was refused outright in
 * airplane mode ("needs the network and there is none") with the file sitting on the disk.
 * Reported by Dewi on 2026-08-06: a Novara episode, downloaded, queued, and silent on a plane.
 *
 * Four variants because there are exactly four things the app can do with something playable,
 * and each maps to one call at the edge. A third pillar cannot be added without every `when`
 * over this failing to compile, which is the point.
 */
public sealed interface PlayRoute {

    /** A file on disk with a picture in it. */
    public data class VideoFile(
        public val playable: PlayableItem,
        public val path: String,
    ) : PlayRoute

    /** A file on disk with audio alone — a podcast enclosure, or a video fetched audio-only. */
    public data class AudioFile(
        public val playable: PlayableItem,
        public val path: String,
    ) : PlayRoute

    /** Re-resolve and stream a video. The watch URL, never a stream URL: those expire. */
    public data class VideoStream(
        public val playable: PlayableItem,
        public val watchUrl: HttpUrl,
    ) : PlayRoute

    /**
     * Stream one audio URL.
     *
     * [viaAudioOnlyUrl] marks the case where the source offered a separate audio-only address
     * and we took it — a torrent remuxed by the home server, 2.1 MB/min against 15.2.
     */
    public data class AudioStream(
        public val playable: PlayableItem,
        public val viaAudioOnlyUrl: Boolean,
    ) : PlayRoute

    public data class Refused(public val reason: Refusal) : PlayRoute
}

/**
 * Which route to play [this] by, given any copy on disk and how playback is set up.
 *
 * Pure and total, so every combination is unit-testable without a player, a network or a
 * database — and so the rule cannot drift apart per pillar again.
 */
public fun PlayableItem.routeNow(
    onDisk: LocalCopy?,
    offline: Boolean,
    audioPreferred: Boolean,
    streamRefused: Boolean = false,
): PlayRoute {
    // A handle may already point at a file (Library plays these); the store answers for
    // everything else, because a handle is fixed when the item is queued and the download
    // finishes long afterwards.
    val copy = handle.ownCopy() ?: onDisk
    val usable = copy?.takeIf { it.isWorthPlaying(handle.pillar, offline, audioPreferred, streamRefused) }
    val audioUrl = (handle as? PlayHandle.Podcast)?.audioUrl?.takeIf { audioPreferred }
    val hasStream = handle is PlayHandle.Video || item.mediaUrl != null || audioUrl != null
    return when {
        usable != null -> playedFromDisk(usable).let { local ->
            if (usable.audioOnly || handle.pillar == MediaKind.PODCAST) {
                PlayRoute.AudioFile(local, usable.path)
            } else {
                PlayRoute.VideoFile(local, usable.path)
            }
        }
        // Checked before the network, because "there is nothing to play" is true either way and
        // is the more useful thing to be told.
        !hasStream -> PlayRoute.Refused(Refusal.NothingToPlay)
        offline -> PlayRoute.Refused(Refusal.NotOnThisDevice)
        // Asking again for a stream that has just been given up on would loop. Reaching here
        // means there was no copy worth playing either, so there is genuinely nothing left.
        streamRefused -> PlayRoute.Refused(Refusal.StreamWillNotPlay)
        handle is PlayHandle.Video -> PlayRoute.VideoStream(this, handle.watchUrl)
        audioUrl != null -> PlayRoute.AudioStream(copy(item = item.copy(mediaUrl = audioUrl)), viaAudioOnlyUrl = true)
        else -> PlayRoute.AudioStream(this, viaAudioOnlyUrl = false)
    }
}

/**
 * The same item played from a file rather than the network. A video fetched audio-only is an
 * audio file, so it plays as one.
 *
 * Shared with [DownloadedMedia.offline]: the Library and the queue arrive at a local copy by
 * different paths and must make the same handle out of it, or one of them streams a file that
 * is already on the disk.
 */
public fun PlayableItem.playedFromDisk(copy: LocalCopy): PlayableItem = copy(
    handle = if (copy.audioOnly || handle.pillar == MediaKind.PODCAST) {
        PlayHandle.Podcast(copy.path)
    } else {
        PlayHandle.LocalVideo(copy.path)
    },
)

/** The copy a handle already carries, when it carries one. */
private fun PlayHandle.ownCopy(): LocalCopy? = when (this) {
    is PlayHandle.LocalVideo -> LocalCopy(localPath, audioOnly = false)
    // A podcast file is the whole thing; there is no fuller version to prefer over it.
    is PlayHandle.Podcast -> localPath?.let { LocalCopy(it, audioOnly = false) }
    is PlayHandle.Video -> null
}

/**
 * Whether a copy on disk beats streaming.
 *
 * Almost always yes — it costs no data and cannot stall. The exception is the queue's
 * audio-only copy of a *video* while you are actually watching: playing that would silently
 * take the picture away, so we stream instead and keep the file for the plane. Dewi's call,
 * 2026-08-06, on "prefer local always, or only when offline?".
 *
 * [streamRefused] is the case where that exception stops applying, added 2026-08-14. The rule is
 * about not *silently* dropping the picture when a perfectly good stream exists — but once the
 * stream has failed every retry, the choice is not "audio or video", it is "audio or nothing", and
 * skipping to the next item is the worse answer. Report 0.1.383: an audio copy of the WarFronts
 * video was on the disk (`copy=audio-only`, 29 of 29 queue items downloaded) through three failed
 * attempts, and the app never once reached for it.
 */
private fun LocalCopy.isWorthPlaying(
    pillar: MediaKind,
    offline: Boolean,
    audioPreferred: Boolean,
    streamRefused: Boolean,
): Boolean = !audioOnly || pillar == MediaKind.PODCAST || audioPreferred || offline || streamRefused
