package com.dewijones92.totum.video

import com.dewijones92.totum.common.AudioTrackTag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.audioLanguagePreference
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.audioTag
import com.dewijones92.totum.ytdlp.audioTracks
import com.dewijones92.totum.ytdlp.bestAudioFormat

/**
 * One selectable streaming quality for a video. [videoUrl] is either a muxed
 * stream (then [audioUrl] is null) or a video-only stream paired with a
 * separate [audioUrl] to be merged at playback — that's how qualities above
 * YouTube's muxed ceiling (~720p) stream.
 */
public data class VideoQuality(
    /** Stable id (the height), so a selection survives a re-resolve. */
    val id: String,
    val label: String,
    val height: Int,
    val videoUrl: HttpUrl,
    val audioUrl: HttpUrl?,
    /** The stream's video codec, for diagnostics and codec-aware selection. */
    val codec: String? = null,
    /**
     * What this quality's SOUND is — the language, and whether it is a dub.
     *
     * Carried so the log line written when a stream is handed to the player can say which
     * language is about to play. Report 0.1.373 could not: it named the audio-only pick while
     * a muxed stream chosen by a different rule was what played, in German.
     */
    val audio: AudioTrackTag = AudioTrackTag.Unknown,
)

/**
 * What was chosen, in which language, and what else was on offer — for the resolve log.
 *
 * "The video was in the wrong language" was unanswerable from a report for the second time on
 * 2026-08-09: the line named the audio-only pick, while the stream that actually played was a
 * muxed one chosen by a different rule. It now names the format that plays, so the two can
 * never quietly disagree again. Empty when the video offers no audio, so a single-track
 * video's log stays as short as it was.
 */
public fun MediaMetadata.audioChoice(wanted: List<String>, playing: MediaFormat?): String {
    val chosen = playing ?: bestAudioFormat(wanted) ?: return ""
    val tag = chosen.audioTag
    val offered = audioTracks(wanted).joinToString("/") { it.label }.ifEmpty { "nothing stated" }
    return ", audio ${tag.languageCode ?: "unstated"} (${tag.label}) via format ${chosen.formatId}" +
        "; offered $offered; wanted ${wanted.joinToString("/").ifEmpty { "anything" }}"
}

/**
 * The selectable qualities, **filtered to what this device can actually decode**, highest
 * first. A muxed format at a given height wins (one stream, most reliable); otherwise a
 * video-only stream is paired with the best audio-only track for merging. Heights with no
 * usable stream are dropped.
 *
 * Above 1080p YouTube publishes only video-only VP9/AV1, so every high quality goes
 * down the merge path with an arbitrary codec. Offering one the device can't decode
 * meant selecting it just stopped playback, which is why [support] is consulted here
 * rather than left to fail at the decoder. Where several codecs are decodable at a
 * height, the most likely to be hardware-accelerated wins.
 *
 * [wanted] is the audio language to prefer. It matters here and not only in [bestAudioFormat]
 * because YouTube's HLS manifest publishes one muxed variant **per dubbed language** at the
 * same height, and taking the first of them is how report 0.1.373 watched an English talk in
 * German. A muxed stream whose sound is worse than the best audio-only track loses to the
 * merge path — a second stream costs data, and playing the wrong language costs the video.
 */
public fun MediaMetadata.videoQualities(
    support: VideoCodecSupport = VideoCodecSupport.Permissive,
    wanted: List<String> = emptyList(),
): List<VideoQuality> {
    val bestAudio = bestAudioFormat(wanted)
    val byAudio = audioLanguagePreference(wanted)
    // The best sound available ANYWHERE on this video, muxed streams included — the bar every
    // height has to clear. Comparing against the best audio-only track alone was not enough:
    // asking for German then kept a 720p entry that spoke English, because English was also
    // the best audio-only there was.
    val bestSound = formats.filter { it.hasAudio && it.url != null }
        .map { it.audioTag }
        .maxWithOrNull(byAudio) ?: AudioTrackTag.Unknown

    return formats
        .filter { it.hasVideo && it.height != null && it.url != null }
        .filter { support.canDecode(it.videoCodec, it.width, it.height) }
        .groupBy { it.height!! }
        .mapNotNull { (height, atHeight) ->
            val decodable = atHeight.sortedBy {
                it.videoCodec.codecPreference(support.isHardware(it.videoCodec, it.width, it.height))
            }
            // Stable sort, so among equally-good sound the codec order above survives.
            val muxed = decodable.filter { it.hasAudio }
                .sortedWith(compareByDescending(byAudio) { it.audioTag })
                .firstOrNull()
            val videoOnly = decodable.firstOrNull { !it.hasAudio }
            val muxedSoundsRight = muxed != null && byAudio.compare(muxed.audioTag, bestSound) >= 0
            val mergeSoundsRight = bestAudio != null && byAudio.compare(bestAudio.audioTag, bestSound) >= 0
            val merged = videoOnly?.takeIf { mergeSoundsRight }
            when {
                muxedSoundsRight -> muxed!!.asQuality(height, audio = null)
                merged != null -> merged.asQuality(height, bestAudio)
                // Everything at this height speaks a language you did not ask for, and there is
                // no video-only stream to pair with the audio you did. Dropping the height is
                // right: **the ladder is the ladder for the track you are listening to.** It was
                // offered anyway at first, and the auto-pick — which takes the tallest — went
                // straight back to the German dub the fix was supposed to stop. Choose that
                // track in the audio menu and its own heights appear.
                else -> null
            }
        }
        .sortedByDescending { it.height }
}

private fun MediaFormat.asQuality(height: Int, audio: MediaFormat?): VideoQuality? =
    HttpUrl.parse(url!!)?.let { video ->
        VideoQuality(
            id = "$height",
            label = "${height}p",
            height = height,
            videoUrl = video,
            audioUrl = audio?.url?.let(HttpUrl::parse),
            codec = videoCodec,
            audio = (audio ?: this).audioTag,
        )
    }
