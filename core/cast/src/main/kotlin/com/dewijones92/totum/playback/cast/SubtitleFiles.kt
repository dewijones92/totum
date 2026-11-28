package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.File

internal object SubtitleFiles {

    fun reply(
        config: CastSubtitleSource,
        mediaId: String,
        get: (String, String?) -> UpstreamReply = Upstream::get,
    ): HttpReply {
        val text = if (!config.location.startsWith("http")) {
            Diag.log("cast", "subtitles ${config.language} for $mediaId: from the film's own track")
            File(CastStreamServer.pathOf(config.location)).readText()
        } else {
            val reply = get(config.location, null)
            Diag.log("cast", "subtitles ${config.language} for $mediaId: upstream ${reply.status}")
            if (reply.status != Http.OK) {
                return HttpReply.Stream(reply.status, "text/vtt", reply.contentLength, emptyMap(), reply::body)
            }
            reply.body().use { it.readBytes() }.toString(Charsets.UTF_8)
        }
        val asVtt = if (text.trimStart('﻿').startsWith("WEBVTT")) {
            text
        } else {
            Diag.log("cast", "subtitles ${config.language} for $mediaId: SubRip text, made WebVTT for the TV")
            EmbeddedSubtitles.srtToVtt(text)
        }
        val cleaned = TvCaptions.clean(asVtt)
        Diag.log(
            "cast",
            "subtitles ${config.language} for $mediaId cleaned for the TV, placed at ${TvCaptions.SAFE_PLACE}: " +
                "cue settings removed ${cleaned.settingsRemoved.ifEmpty { "none" }}, " +
                "${cleaned.inlineTagsRemoved} word-timing tag(s) removed",
        )
        return HttpReply.Bytes(Http.OK, "text/vtt", cleaned.text.toByteArray(Charsets.UTF_8))
    }
}
