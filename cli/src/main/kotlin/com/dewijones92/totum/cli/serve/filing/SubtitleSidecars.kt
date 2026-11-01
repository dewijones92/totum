package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.runProcess
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.ask.AskJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

class SubtitleTrack(val index: Int, val codec: String, val language: String?, val title: String?)

object Sidecars {
    private val TEXT = setOf("ass", "ssa", "subrip", "srt", "mov_text", "webvtt", "text")
    private val FONT = Regex("</?font[^>]*>", RegexOption.IGNORE_CASE)

    fun tracks(ffprobeJson: String): List<SubtitleTrack> =
        runCatching { AskJson.parseToJsonElement(ffprobeJson).jsonObject["streams"]!!.jsonArray }
            .getOrDefault(emptyList())
            .map { it.jsonObject }
            .filter { it["codec_type"]?.jsonPrimitive?.contentOrNull == "subtitle" }
            .mapNotNull { stream ->
                val tags = stream["tags"] as? JsonObject
                SubtitleTrack(
                    index = stream["index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                    codec = stream["codec_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    language = tags?.get("language")?.jsonPrimitive?.contentOrNull?.lowercase()?.takeIf { it != "und" },
                    title = tags?.get("title")?.jsonPrimitive?.contentOrNull,
                )
            }

    fun english(tracks: List<SubtitleTrack>): List<SubtitleTrack> {
        val text = tracks.filter { it.codec in TEXT }
        val tagged = text.filter { it.language == "eng" || it.language == "en" }
        return tagged.ifEmpty { if (text.any { it.language != null }) emptyList() else text }
    }

    fun names(video: File, tracks: List<SubtitleTrack>): List<Pair<SubtitleTrack, File>> {
        val used = mutableSetOf<String>()
        return tracks.map { track ->
            val title = track.title.orEmpty().lowercase()
            val kind = when {
                "forced" in title -> ".forced"
                "sdh" in title || "hearing" in title -> ".sdh"
                else -> ""
            }
            val stem = "${video.nameWithoutExtension}.eng$kind"
            val name = generateSequence(1) { it + 1 }.map { n -> if (n == 1) "$stem.srt" else "$stem.$n.srt" }
                .first { it !in used }
            used += name
            track to File(video.parentFile, name)
        }
    }

    fun cleaned(srt: String): String = srt.replace(FONT, "")
}

internal class SidecarMaker(
    private val ffprobe: String = "ffprobe",
    private val ffmpeg: String = "ffmpeg",
    private val run: (List<String>, Long) -> ProcessResult = ::runProcess,
) {
    fun make(video: File): List<File> {
        val probe = run(
            listOf(
                ffprobe,
                "-v",
                "error",
                "-show_entries",
                "stream=index,codec_type,codec_name:stream_tags=language,title",
                "-of",
                "json",
                video.path
            ),
            PROBE_MS,
        )
        val all = Sidecars.tracks(probe.stdout)
        val chosen = Sidecars.english(all)
        if (chosen.isEmpty()) {
            Diag.log("filing", "no English text subtitles inside ${video.name} (${all.size} subtitle track(s))")
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        return Sidecars.names(video, chosen).mapNotNull { (track, out) ->
            if (out.exists()) {
                return@mapNotNull null.also {
                    Diag.log(
                        "filing",
                        "${out.name} already there; left alone"
                    )
                }
            }
            val made =
                run(
                    listOf(ffmpeg, "-nostdin", "-loglevel", "error", "-y", "-i", video.path) +
                        listOf("-map", "0:${track.index}", "-c:s", "srt", out.path),
                    CONVERT_MS
                )
            if (made.exitCode != 0 || !out.isFile) {
                Diag.warn(
                    "filing",
                    "subtitle track ${track.index} of ${video.name} not extracted: ${made.stderr.take(LOGGED)}"
                )
                out.delete()
                return@mapNotNull null
            }
            val text = Sidecars.cleaned(out.readText())
            if (!seen.add(md5(text))) {
                out.delete()
                return@mapNotNull null.also {
                    Diag.log(
                        "filing",
                        "track ${track.index} of ${video.name} repeats another; dropped"
                    )
                }
            }
            out.writeText(text)
            Diag.log("filing", "subtitles for ${video.name}: ${out.name} from track ${track.index} (${track.codec})")
            out
        }
    }

    private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") {
        "%02x".format(it)
    }

    private companion object {
        const val PROBE_MS = 60_000L
        const val CONVERT_MS = 300_000L
        const val LOGGED = 300
    }
}
