package com.dewijones92.totum.innertube.player

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.SubtitleFormat
import com.dewijones92.totum.common.SubtitleTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/**
 * Reads a `/player` response into [StreamingData].
 *
 * Keeps formats that have NO url, unlike every URL extractor — that is the whole point.
 * They are what YouTube offers over SABR, and the app needs to know they exist even while
 * it cannot fetch them.
 *
 * Both format lists are read and merged: `formats` holds the legacy muxed streams (itag 18
 * and friends) and `adaptiveFormats` the separate video/audio ones. A video restricted to
 * SABR keeps exactly one entry in the first list and a full ladder, all URL-less, in the
 * second — which is how a 1080p video ends up playing at 360p.
 */
public object PlayerResponseParser {

    private val json = Json { ignoreUnknownKeys = true }

    private const val PADDING = 4

    public fun parse(body: String): PlayerResult {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return PlayerResult.Failure("Unparseable player response")

        val status = (root["playabilityStatus"] as? JsonObject)
        when (val state = status?.stringAt("status")) {
            null, "OK" -> Unit
            // The details are kept even though the streams are refused, and that is the point.
            // A refusal still describes the video perfectly — title, author, length — while the
            // signed-in TV response that CAN supply streams carries none of that (measured
            // 2026-08-01: its videoDetails holds ids, length and a thumbnail, nothing readable).
            // Between them there is a whole video; discarding this half left the other one
            // unusable.
            else -> return PlayerResult.Unplayable(
                listOfNotNull(state, status.stringAt("reason")).joinToString(": "),
                details = (root["videoDetails"] as? JsonObject)?.toDetails(),
            )
        }

        val streaming = root["streamingData"] as? JsonObject
            ?: return PlayerResult.Failure("Player response carried no streamingData")
        val formats = listOf("formats", "adaptiveFormats")
            .flatMap { key -> (streaming[key] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.toFormat() }

        return PlayerResult.Success(
            StreamingData(
                formats = formats,
                serverAbrStreamingUrl = streaming.stringAt("serverAbrStreamingUrl")?.let(HttpUrl::parse),
                ustreamerConfig = root.ustreamerConfig(),
            ),
            details = (root["videoDetails"] as? JsonObject)?.toDetails(),
            subtitles = root.captionTracks(),
        )
    }

    private fun JsonObject.toDetails(): PlayerDetails? {
        val videoId = stringAt("videoId") ?: return null
        val title = stringAt("title") ?: return null
        return PlayerDetails(
            videoId = videoId,
            title = title,
            author = stringAt("author"),
            channelId = stringAt("channelId"),
            // A string in the response, and absent for a live stream.
            lengthSeconds = stringAt("lengthSeconds")?.toLongOrNull()?.takeIf { it > 0 },
            thumbnailUrl = bestThumbnailUrl(),
            description = stringAt("shortDescription"),
            isLive = this["isLiveContent"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }

    /** The largest thumbnail offered; they arrive smallest-first. */
    private fun JsonObject.bestThumbnailUrl(): HttpUrl? =
        ((this["thumbnail"] as? JsonObject)?.get("thumbnails") as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.stringAt("url") }
            ?.lastOrNull()
            ?.let(HttpUrl::parse)

    /**
     * Caption tracks, asked for as WebVTT.
     *
     * YouTube hands out `fmt=srv3` — its own XML — which nothing in Media3 renders. The same
     * endpoint serves WebVTT for the asking, so the format is asked for in the URL rather than a
     * converter being written. See [askingForVtt]: this used to be a `replace` of `fmt=srv3`, which
     * did nothing when the `baseUrl` carried no `fmt` — which it usually does not.
     *
     * An auto-generated track is marked by a `vssId` beginning `a.`, which is worth surfacing
     * because auto captions are wrong often enough to want to know.
     */
    private fun JsonObject.captionTracks(): List<SubtitleTrack> {
        val tracks = (
            (this["captions"] as? JsonObject)
                ?.get("playerCaptionsTracklistRenderer") as? JsonObject
            )
            ?.get("captionTracks") as? JsonArray
            ?: return emptyList()
        return tracks.mapNotNull { entry ->
            val track = entry as? JsonObject ?: return@mapNotNull null
            val base = track.stringAt("baseUrl") ?: return@mapNotNull null
            val language = track.stringAt("languageCode") ?: return@mapNotNull null
            val auto = track.stringAt("vssId")?.startsWith("a.") == true
            val name = ((track["name"] as? JsonObject)?.get("runs") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.stringAt("text") }
                ?.joinToString("")
                ?.ifBlank { null }
                ?: language
            SubtitleTrack(
                languageCode = language,
                label = if (auto) "$name (auto-generated)" else name,
                url = HttpUrl.parse(askingForVtt(base)) ?: return@mapNotNull null,
                autoGenerated = auto,
                format = SubtitleFormat.VTT,
            )
        }
    }

    private fun JsonObject.toFormat(): PlayableFormat? {
        val itag = this["itag"]?.jsonPrimitive?.longOrNull?.toInt() ?: return null
        return PlayableFormat(
            itag = itag,
            mimeType = stringAt("mimeType"),
            height = this["height"]?.jsonPrimitive?.longOrNull?.toInt(),
            bitrate = this["bitrate"]?.jsonPrimitive?.longOrNull,
            url = stringAt("url")?.let(HttpUrl::parse),
            lastModified = stringAt("lastModified")?.toLongOrNull(),
            xtags = stringAt("xtags"),
            fps = this["fps"]?.jsonPrimitive?.longOrNull?.toInt(),
            contentLength = stringAt("contentLength")?.toLongOrNull(),
        )
    }

    /**
     * The SABR config, base64url-DECODED.
     *
     * YouTube writes it base64url without padding, which `Base64.getUrlDecoder` rejects, so the
     * padding is restored first. Getting this wrong yields a config the server calls malformed,
     * which is indistinguishable from not sending one.
     */
    private fun JsonObject.ustreamerConfig(): ByteArray? {
        val encoded = (this["playerConfig"] as? JsonObject)
            ?.let { it["mediaCommonConfig"] as? JsonObject }
            ?.let { it["mediaUstreamerRequestConfig"] as? JsonObject }
            ?.stringAt("videoPlaybackUstreamerConfig")
            ?: return null
        val padded = encoded + "=".repeat((PADDING - encoded.length % PADDING) % PADDING)
        return runCatching { Base64.getUrlDecoder().decode(padded) }.getOrNull()
    }

    private fun JsonObject.stringAt(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }
}

/**
 * The same caption URL, asking for WebVTT whatever it asked for before.
 *
 * `replace("fmt=srv3", "fmt=vtt")` was a silent no-op on a `baseUrl` with no `fmt` — which is what
 * the InnerTube player response hands out — so the app declared its track `text/vtt` and YouTube
 * served srv3 XML. Media3 then failed the load with `contentIsMalformed=true`, twice per play,
 * which is why captions never appeared on that route. Proven by the app's own instrumentation in CI
 * run 35525069446: `declared=text/vtt asks=no fmt`.
 */
internal fun askingForVtt(base: String): String {
    val withoutFmt = base
        .replace(Regex("""([?&])fmt=[^&]*&"""), "$1")
        .replace(Regex("""[?&]fmt=[^&]*$"""), "")
    val separator = if ("?" in withoutFmt) "&" else "?"
    return "$withoutFmt$separator$FMT_VTT"
}

private const val FMT_VTT = "fmt=vtt"
