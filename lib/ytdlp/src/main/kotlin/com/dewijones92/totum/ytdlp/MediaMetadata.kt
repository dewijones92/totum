package com.dewijones92.totum.ytdlp

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleTrack
import com.dewijones92.totum.common.audioLanguagePreference

/** Result of asking the engine to extract [MediaMetadata] for a URL. */
public sealed interface ExtractionResult {

    public data class Success(val metadata: MediaMetadata) : ExtractionResult

    /** Expected, recoverable failures — modelled as values, not exceptions. */
    public sealed interface Failure : ExtractionResult {
        /** No extractor recognises this URL. */
        public data class UnsupportedUrl(val url: HttpUrl) : Failure

        /** The network was unreachable or the request timed out. */
        public data class Network(val detail: String) : Failure

        /** yt-dlp recognised the URL but extraction failed (geo-block, login wall, removal…). */
        public data class Extractor(val detail: String) : Failure
    }
}

/** What yt-dlp knows about a piece of media, without downloading it. */
public data class MediaMetadata(
    val id: String,
    val title: String,
    val uploader: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val formats: List<MediaFormat>,
    /** The uploader's description/notes for this media, when the extractor provides one. */
    val description: String? = null,
    /**
     * The uploader's own page, when the extractor provides one — for YouTube the
     * canonical `/channel/UC…` URL. Lets a media row navigate to its source
     * without the caller having to know the channel up front.
     */
    val uploaderUrl: String? = null,
    /** Chapters yt-dlp parsed from the description/metadata, earliest first; empty if none. */
    val chapters: List<ChapterInfo> = emptyList(),
    /**
     * Renderable subtitle tracks, author-provided first then auto-generated. Already
     * filtered to formats a player can decode and to a sane set of languages — see
     * the bridge's parsing, where YouTube's ~100 machine translations are dropped.
     */
    val subtitles: List<SubtitleTrack> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(durationSeconds == null || durationSeconds > 0) { "durationSeconds must be positive when present" }
    }
}

/** One chapter as yt-dlp reports it: a start offset in seconds and a title. */
public data class ChapterInfo(val startSeconds: Double, val title: String)

/** One downloadable/streamable representation of the media. */
public data class MediaFormat(
    val formatId: String,
    val container: String,
    val width: Int?,
    val height: Int?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val fileSizeBytes: Long?,
    /** Direct stream URL when the extractor provides one. */
    val url: String?,
    /**
     * The extractor's codec strings (e.g. `vp09.00.50.08`, `av01.0.08M.08`,
     * `avc1.640028`, `mp4a.40.2`), or null when unknown. Needed because a device
     * that can't decode a codec must not be offered that stream: above 1080p
     * YouTube only publishes VP9/AV1, and picking blind means silent playback
     * failure.
     */
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    /**
     * BCP-47 language of this track (`en`, `en-US`, `hi`), or null when the extractor says
     * nothing. Only meaningful for audio: YouTube publishes dubbed tracks alongside the
     * original, and without this a track is just a bitrate.
     */
    val language: String? = null,
    /**
     * The extractor's preference for this track's language, higher being better; YouTube's
     * ORIGINAL track scores 10 and a dub scores less.
     *
     * Carried because bitrate alone picks the wrong one. Audio used to be chosen purely by file
     * size, so on any video whose dub is encoded larger than the original, the app played the
     * dub — a video in a language nobody asked for, with nothing in the logs to say why.
     */
    val languagePreference: Int? = null,
) {
    init {
        require(formatId.isNotBlank()) { "formatId must not be blank" }
        require(hasVideo || hasAudio) { "a format must carry audio, video, or both" }
        require(hasVideo || (width == null && height == null)) {
            "audio-only formats cannot have video dimensions"
        }
    }

    public val isAudioOnly: Boolean
        get() = hasAudio && !hasVideo
}

/**
 * The format to hand to a player: pre-muxed audio+video in the language you want at the
 * highest resolution, else the best audio-only stream. Null when nothing is directly
 * streamable.
 *
 * **Language before height, deliberately.** It used to be height alone, and on a video with
 * dubs that picks whichever dub happens to be tallest — report 0.1.373 watched an English
 * conference talk in automatic German because of this line.
 */
public fun MediaMetadata.bestPlayableFormat(wanted: List<String> = emptyList()): MediaFormat? {
    val streamable = formats.filter { it.url != null }
    return streamable.filter { it.hasVideo && it.hasAudio }
        .maxWithOrNull(byAudioThen(wanted, compareBy { it.height ?: 0 }))
        ?: bestAudioFormat(wanted)
}

/**
 * The best audio-only stream in the language you want, for merging or for "Listen".
 *
 * Size alone used to decide, so on any video whose dub is encoded larger than the original the
 * app played a language nobody asked for.
 */
public fun MediaMetadata.bestAudioFormat(wanted: List<String> = emptyList()): MediaFormat? =
    formats.filter { it.isAudioOnly && it.url != null }
        .maxWithOrNull(byAudioThen(wanted, compareBy { it.fileSizeBytes ?: 0 }))

/** The best audio-only stream's URL — see [bestAudioFormat]. */
public fun MediaMetadata.bestAudioUrl(wanted: List<String> = emptyList()): HttpUrl? =
    bestAudioFormat(wanted)?.url?.let(HttpUrl::parse)

/**
 * The distinct audio tracks on offer, best-first — what an audio-track menu lists.
 *
 * Keyed by language, because that is what a person is choosing between; a video with four
 * bitrates of one track offers one choice, not four. Empty when the video says nothing about
 * any of its audio, so a menu with nothing to decide is never shown.
 */
public fun MediaMetadata.audioTracks(wanted: List<String> = emptyList()): List<AudioTrackTag> {
    val tags = formats.filter { it.hasAudio && it.url != null }.map { it.audioTag }
    if (tags.none { it.languageCode != null }) return emptyList()
    return tags.filter { it.languageCode != null }
        .distinctBy { it.languageCode!!.lowercase() }
        .sortedWith(audioLanguagePreference(wanted).reversed())
}

/**
 * What this format's sound is, from the extractor's fields and — where they say nothing —
 * from the stream URL, which labels itself. See [AudioTrackTag.inUrl].
 */
public val MediaFormat.audioTag: AudioTrackTag
    get() {
        if (!hasAudio) return AudioTrackTag.Unknown
        val declared = AudioTrackTag.inUrl(url)
        return AudioTrackTag(
            languageCode = language ?: declared.languageCode,
            original = declared.original || (languagePreference ?: 0) >= ORIGINAL_LANGUAGE_PREFERENCE,
            dubbed = declared.dubbed,
        )
    }

/** Audio language first, [tieBreak] second — the order every stream picker uses. */
private fun byAudioThen(wanted: List<String>, tieBreak: Comparator<MediaFormat>): Comparator<MediaFormat> =
    compareBy<MediaFormat> { if (it.isDurable) 1 else 0 }
        .then(compareBy(audioLanguagePreference(wanted)) { format: MediaFormat -> format.audioTag })
        .then(tieBreak)

/**
 * Whether this stream's URL has been through the `n` solve — the difference between a stream that
 * plays to the end and one that stops about a megabyte in.
 *
 * Measured 2026-08-18, from Dewi's own connection, when the app could play nothing it had not already
 * downloaded. `ANDROID_VR`'s URLs carry no `n` and were refused 403 at any offset past the first
 * megabyte; `WEB_EMBEDDED_PLAYER`'s carry one and served the middle of the file. `n` is the parameter a
 * JavaScript runtime exists to solve, so its presence means a real client's request rather than a
 * guess, and YouTube treats it accordingly.
 *
 * Ranked FIRST, ahead of language and size, because durability is not something those can express: with
 * every client requested at once, yt-dlp still handed back `ANDROID_VR`'s audio — the largest, the right
 * language, and unfetchable. A URL with no `n` at all (a podcast enclosure, a torrent) is simply not
 * YouTube's and loses nothing by this, since it is only ever compared against its own kind.
 *
 * Anchored on the parameter boundary so `ns=` and `sn=`, which appear on the very URLs that fail, are
 * not mistaken for it.
 */
private val MediaFormat.isDurable: Boolean
    get() = url?.let { DECIPHERED_N.containsMatchIn(it) } == true

private val DECIPHERED_N = Regex("""[?&]n=[^&]+""")

/** yt-dlp scores the uploader's own track 10 and everything else below it. */
private const val ORIGINAL_LANGUAGE_PREFERENCE = 10
