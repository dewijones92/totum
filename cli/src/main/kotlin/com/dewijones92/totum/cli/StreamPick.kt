package com.dewijones92.totum.cli

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.audioTag
import com.dewijones92.totum.ytdlp.audioTracks
import com.dewijones92.totum.ytdlp.bestAudioFormat
import com.dewijones92.totum.ytdlp.bestPlayableFormat
import java.util.Locale

/** What the CLI decided to play, and enough about it to say so. */
internal data class StreamPick(
    val url: HttpUrl,
    val title: String,
    val uploader: String?,
    val audio: AudioTrackTag,
    /** Whether the STREAM carries a picture — not whether one will be shown. */
    val carriesVideo: Boolean,
    val formatId: String,
    val otherTracks: List<AudioTrackTag>,
)

/**
 * The stream to play — chosen by **exactly the same rules as the phone**.
 *
 * This is the whole point of the CLI living in this repo rather than being a shell script around
 * yt-dlp: `bestAudioFormat` and `bestPlayableFormat` are the app's own pickers, so the auto-dub
 * avoidance and the original-track preference apply here for free, and a change to either is a
 * change to both. A second implementation would be wrong within a fortnight.
 */
internal fun MediaMetadata.pick(wanted: List<String>, watch: Boolean): StreamPick? {
    val format = if (watch) bestPlayableFormat(wanted) else bestAudioFormat(wanted) ?: bestPlayableFormat(wanted)
    val url = format?.url?.let(HttpUrl::parse) ?: return null
    return StreamPick(
        url = url,
        title = title,
        uploader = uploader,
        audio = format.audioTag,
        carriesVideo = format.hasVideo,
        formatId = format.formatId,
        otherTracks = audioTracks(wanted).filterNot { it.languageCode == format.audioTag.languageCode },
    )
}

/**
 * The languages to prefer, from the machine's own locale — the desktop equivalent of the phone's
 * language list, and the same reason: a dub nobody asked for is the failure being avoided.
 */
internal fun deviceLanguages(): List<String> =
    listOfNotNull(Locale.getDefault().language.ifBlank { null })
