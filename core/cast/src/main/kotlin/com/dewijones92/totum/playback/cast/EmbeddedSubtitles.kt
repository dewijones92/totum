package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import java.io.File
import java.util.concurrent.TimeUnit

public object EmbeddedSubtitles {

    internal data class Stream(val index: Int, val codec: String, val language: String)

    internal data class Kept(val file: File, val language: String)

    private const val DIR = "embedded-subs"
    private const val DONE = "done"
    private const val TIMEOUT_MIN = 10L
    private const val UNKNOWN = "und"
    private const val LOG_CHARS = 300
    private const val BYTE_ORDER_MARK = '\uFEFF'
    private const val CODEC_FIELD = 1
    private const val LANGUAGE_FIELD = 2
    private val TIME_COMMA = Regex("""(\d{2}:\d{2}:\d{2}),(\d{3})""")

    public fun isDone(tools: MediaTools, source: File): Boolean = File(dirFor(tools, source), DONE).exists()

    internal fun kept(tools: MediaTools, source: File): List<Kept> {
        val dir = dirFor(tools, source)
        if (!File(dir, DONE).exists()) return emptyList()
        return dir.listFiles { file -> file.extension == "vtt" }.orEmpty().sortedBy { it.name }
            .map { Kept(it, it.nameWithoutExtension.substringAfter('-', UNKNOWN)) }
    }

    public fun prepare(tools: MediaTools, source: File): Int = synchronized(lockFor(source)) {
        runCatching { prepareNow(tools, source) }.getOrElse {
            Diag.warn("subtitles", "saving ${source.name}'s own subtitles failed; it is tried again next time", it)
            0
        }
    }

    private fun prepareNow(tools: MediaTools, source: File): Int {
        if (isDone(tools, source)) return kept(tools, source).size
        val ffprobe = tools.ffprobe
        val ffmpeg = tools.ffmpeg
        if (ffprobe == null || ffmpeg == null || !ffprobe.exists() || !ffmpeg.exists()) {
            Diag.warn(
                "subtitles",
                "no bundled ffmpeg/ffprobe, so ${source.name}'s own subtitles cannot be saved for the TV"
            )
            return 0
        }
        val dir = dirFor(tools, source).apply {
            deleteRecursively()
            mkdirs()
        }
        val started = System.currentTimeMillis()
        val streams = run(
            listOf(
                ffprobe.absolutePath, "-v", "error", "-select_streams", "s",
                "-show_entries", "stream=index,codec_name:stream_tags=language", "-of", "csv=p=0", source.absolutePath,
            )
        )?.let(::streamsOf).orEmpty()
        val saved = streams.filter { it.codec == "subrip" }.mapNotNull { stream ->
            val srt = File(dir, "${stream.index}.srt")
            run(
                listOf(
                    ffmpeg.absolutePath, "-v", "error", "-y", "-i", source.absolutePath,
                    "-map", "0:${stream.index}", "-c", "copy", "-f", "srt", srt.absolutePath,
                )
            ) ?: return@mapNotNull null
            File(dir, "${stream.index}-${stream.language}.vtt").also {
                it.writeText(srtToVtt(srt.readText()))
                srt.delete()
            }
        }
        File(dir, DONE).writeText(saved.size.toString())
        val skipped = streams.filter { it.codec != "subrip" }.joinToString { "${it.codec} ${it.language}" }
        Diag.log(
            "subtitles",
            "${source.name}: ${saved.size} of its ${streams.size} subtitle track(s) saved for the TV " +
                "in ${System.currentTimeMillis() - started}ms" +
                skipped.takeIf { it.isNotEmpty() }?.let { "; not text Totum converts: $it" }.orEmpty(),
        )
        return saved.size
    }

    internal fun streamsOf(listing: String): List<Stream> = listing.lines().mapNotNull { line ->
        val fields = line.trim().split(',')
        val index = fields.firstOrNull()?.toIntOrNull() ?: return@mapNotNull null
        Stream(
            index,
            fields.getOrNull(CODEC_FIELD).orEmpty(),
            fields.getOrNull(LANGUAGE_FIELD)?.ifBlank { null } ?: UNKNOWN
        )
    }

    internal fun srtToVtt(srt: String): String =
        "WEBVTT\n\n" + srt.replace("\r\n", "\n").replace(TIME_COMMA, "$1.$2").trimStart(BYTE_ORDER_MARK)

    private fun dirFor(tools: MediaTools, source: File): File =
        File(File(tools.keepDir, DIR), "${source.name}-${source.length()}")

    private fun run(command: List<String>): String? {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader =
            Thread {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach {
                        output.appendLine(
                            it
                        )
                    }
                }
            }
        reader.start()
        val finished = process.waitFor(TIMEOUT_MIN, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        reader.join()
        return output.toString().takeIf { finished && process.exitValue() == 0 }.also {
            if (it == null) {
                Diag.warn(
                    "subtitles",
                    "${command.first().substringAfterLast('/')} failed: ${output.take(LOG_CHARS)}"
                )
            }
        }
    }

    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(source: File): Any = locks.getOrPut(source.absolutePath) { Any() }
}
