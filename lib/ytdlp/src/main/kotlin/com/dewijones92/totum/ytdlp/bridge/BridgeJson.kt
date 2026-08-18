package com.dewijones92.totum.ytdlp.bridge

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.ChapterInfo
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.EngineVersions
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.MediaFormat
import com.dewijones92.totum.ytdlp.MediaMetadata
import com.dewijones92.totum.ytdlp.VideoSearchEntry
import com.dewijones92.totum.ytdlp.VideoSearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

/** Parsing of the totum_ytdlp.py JSON contract. Pure logic; unit-tested on the JVM. */

private val json = Json { ignoreUnknownKeys = true }

public fun parseVersions(text: String): EngineVersions {
    val obj = json.parseToJsonElement(text).jsonObject
    return EngineVersions(
        ytDlp = obj.stringOrNull("yt_dlp") ?: "unknown",
        python = obj.stringOrNull("python") ?: "unknown",
    )
}

/**
 * Solved `n` parameters, or an empty map with the reason logged.
 *
 * Empty rather than an exception on failure: the caller drops the formats it could not solve
 * and reports "nothing playable", which is a better outcome than taking the playback path down.
 * The bridge reaches into yt-dlp internals and yt-dlp self-updates on every launch, so a wheel
 * that moves them must degrade to "age-restricted videos stopped working" — loudly, but only
 * for those videos.
 */
public fun parseSolvedN(text: String): Map<String, String> {
    val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrElse {
        Diag.warn("resolve", "n solver returned unreadable JSON")
        return emptyMap()
    }
    if (!obj.isOk()) {
        Diag.warn("resolve", "n solver failed: ${obj.stringOrNull("detail")}")
        return emptyMap()
    }
    val solved = obj["solved"] as? JsonObject ?: return emptyMap()
    return solved.mapNotNull { (key, value) -> value.jsonPrimitive.contentOrNull?.let { key to it } }.toMap()
}

public fun parseExtraction(url: HttpUrl, text: String): ExtractionResult {
    val obj = json.parseToJsonElement(text).jsonObject
    return if (obj.isOk()) {
        ExtractionResult.Success(
            obj.getValue("info").jsonObject.toMediaMetadata(url),
            // yt-dlp's own account of anything it lost. These used to be suppressed by `no_warnings`,
            // and they are the only place a SUCCESSFUL extraction says it came back degraded — "formats
            // have been skipped as they are missing a URL … SABR-only streaming experiment" is the
            // sentence that explained a whole day. Inline because this file is at its function limit and
            // one list access does not earn a name.
            (obj["notes"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        )
    } else {
        obj.toFailure(url)
    }
}

public fun parseDownloadCompletion(url: HttpUrl, text: String, fileOf: (String) -> File): DownloadEvent {
    val obj = json.parseToJsonElement(text).jsonObject
    if (!obj.isOk()) return DownloadEvent.Failed(obj.toFailure(url))
    val path = obj.stringOrNull("filepath")
        ?: return DownloadEvent.Failed(ExtractionResult.Failure.Extractor("yt-dlp reported no output file"))
    return DownloadEvent.Completed(fileOf(path))
}

private fun JsonObject.isOk(): Boolean = this["ok"]?.jsonPrimitive?.booleanOrNull == true

private fun JsonObject.toFailure(url: HttpUrl): ExtractionResult.Failure {
    val detail = stringOrNull("detail") ?: "unknown error"
    return when (stringOrNull("kind")) {
        "unsupported" -> ExtractionResult.Failure.UnsupportedUrl(url)
        "network" -> ExtractionResult.Failure.Network(detail)
        else -> ExtractionResult.Failure.Extractor(detail)
    }
}

private fun JsonObject.toMediaMetadata(url: HttpUrl): MediaMetadata = MediaMetadata(
    id = stringOrNull("id") ?: url.value,
    title = stringOrNull("title") ?: "Untitled",
    uploader = stringOrNull("uploader") ?: stringOrNull("channel"),
    durationSeconds = this["duration"]?.jsonPrimitive?.doubleOrNull?.toLong()?.takeIf { it > 0 },
    thumbnailUrl = stringOrNull("thumbnail"),
    formats = arrayAt("formats").mapNotNull { it.jsonObject.toMediaFormatOrNull() },
    description = stringOrNull("description"),
    // channel_url is YouTube's canonical /channel/UC… form; uploader_url may be a
    // handle (/@name), which the channel screen can still resolve.
    uploaderUrl = stringOrNull("channel_url") ?: stringOrNull("uploader_url"),
    chapters = arrayAt("chapters").mapNotNull { it.jsonObject.toChapterOrNull() },
    subtitles = subtitleTracks(),
)

private fun JsonObject.toChapterOrNull(): ChapterInfo? {
    val start = this["start_time"]?.jsonPrimitive?.doubleOrNull ?: return null
    val title = stringOrNull("title") ?: return null
    return ChapterInfo(startSeconds = start, title = title)
}

private fun JsonObject.toMediaFormatOrNull(): MediaFormat? {
    val formatId = stringOrNull("format_id") ?: return null
    val hasVideo = stringOrNull("vcodec").let { it != null && it != "none" }
    val hasAudio = stringOrNull("acodec").let { it != null && it != "none" }
    // Storyboards and other codec-less pseudo-formats are not media.
    if (!hasVideo && !hasAudio) return null
    return MediaFormat(
        formatId = formatId,
        container = stringOrNull("ext") ?: "unknown",
        width = if (hasVideo) this["width"]?.jsonPrimitive?.longOrNull?.toInt() else null,
        height = if (hasVideo) this["height"]?.jsonPrimitive?.longOrNull?.toInt() else null,
        hasVideo = hasVideo,
        hasAudio = hasAudio,
        fileSizeBytes = this["filesize"]?.jsonPrimitive?.longOrNull
            ?: this["filesize_approx"]?.jsonPrimitive?.longOrNull,
        url = stringOrNull("url"),
        videoCodec = stringOrNull("vcodec")?.takeIf { it != "none" },
        audioCodec = stringOrNull("acodec")?.takeIf { it != "none" },
        language = stringOrNull("language"),
        languagePreference = this["language_preference"]?.jsonPrimitive?.longOrNull?.toInt(),
    )
}

public fun parseChannel(url: HttpUrl, text: String): ChannelResult {
    val obj = json.parseToJsonElement(text).jsonObject
    if (!obj.isOk()) {
        val detail = obj.stringOrNull("detail") ?: "unknown error"
        return when (obj.stringOrNull("kind")) {
            "network" -> ChannelResult.Failure.Network(detail)
            else -> ChannelResult.Failure.NotAChannel(url)
        }
    }
    return ChannelResult.Success(
        channelId = obj.stringOrNull("channel_id") ?: url.value,
        title = obj.stringOrNull("title") ?: "Channel",
        videos = obj.arrayAt("videos").mapNotNull { it.jsonObject.toSearchEntryOrNull() },
    )
}

public fun parseSearch(text: String): VideoSearchResult {
    val obj = json.parseToJsonElement(text).jsonObject
    if (!obj.isOk()) {
        return VideoSearchResult.Failure(obj.stringOrNull("detail") ?: "unknown error")
    }
    val entries = obj.arrayAt("entries").mapNotNull { it.jsonObject.toSearchEntryOrNull() }
    return VideoSearchResult.Success(entries)
}

/** Shared flat-entry → [VideoSearchEntry] mapping for search and channel results. */
private fun JsonObject.toSearchEntryOrNull(): VideoSearchEntry? {
    val id = stringOrNull("id") ?: return null
    val watchUrl = stringOrNull("url")?.let(HttpUrl::parse) ?: return null
    return VideoSearchEntry(
        id = id,
        title = stringOrNull("title") ?: "Untitled",
        uploader = stringOrNull("uploader"),
        durationSeconds = this["duration"]?.jsonPrimitive?.doubleOrNull?.toLong()?.takeIf { it > 0 },
        watchUrl = watchUrl,
        thumbnailUrl = stringOrNull("thumbnail"),
        viewCount = this["view_count"]?.jsonPrimitive?.doubleOrNull?.toLong()?.takeIf { it >= 0 },
        membersOnly = this["members_only"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

public fun JsonObject.stringOrNull(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

/** The array at [key], or empty — tolerant of a missing key AND of a present JSON `null` value. */
private fun JsonObject.arrayAt(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()
