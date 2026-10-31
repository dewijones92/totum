package com.dewijones92.totum.playback.cast

import android.content.Context
import com.dewijones92.totum.common.BundledBinaries
import com.dewijones92.totum.common.Diag
import java.io.File
import java.util.concurrent.TimeUnit

internal object VideoRemux {

    private val FORMATS = listOf(
        "mp4" to listOf("-f", "mp4", "-movflags", "+frag_keyframe+empty_moov+default_base_moof+global_sidx"),
        "webm" to listOf("-f", "webm", "-dash", "1", "-cues_to_front", "1"),
    )

    fun videoOnly(
        context: Context,
        source: File,
        label: String,
        durationUs: Long? = null,
        onProgress: (Int) -> Unit = {},
    ): File? {
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, BundledBinaries.FFMPEG)
        if (!ffmpeg.exists()) {
            Diag.warn("cast", "$label: no bundled ffmpeg, so the downloaded video cannot be remuxed for the TV")
            return null
        }
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val stem = "${source.name}-${source.length()}-${source.lastModified()}"
        FORMATS.map { (ext, _) -> File(dir, "$stem.$ext") }.firstOrNull { it.length() > 0 }?.let {
            Diag.log("cast", "$label: reusing the remuxed video ${it.name} (${it.length() / Http.KB}KB)")
            return it
        }
        dir.listFiles()?.filter { it.name.startsWith(source.name + "-") }?.forEach { it.delete() }
        return FORMATS.firstNotNullOfOrNull { (ext, args) ->
            remux(ffmpeg, source, File(dir, "$stem.$ext"), args, Progress(label, durationUs, onProgress))
        }
    }

    private class Progress(val label: String, val durationUs: Long?, val report: (Int) -> Unit)

    private fun remux(
        ffmpeg: File,
        source: File,
        out: File,
        format: List<String>,
        progress: Progress,
    ): File? {
        val label = progress.label
        val partial = File(out.parentFile, out.name + ".part")
        val log = File(out.parentFile, out.name + ".log")
        val progressFile = File(out.parentFile, out.name + ".progress")
        val started = System.currentTimeMillis()
        val input = listOf(ffmpeg.absolutePath, "-hide_banner", "-loglevel", "error", "-y", "-i", source.absolutePath)
        val command = input + listOf("-map", "0:v:0", "-c", "copy", "-progress", progressFile.absolutePath) +
            format + partial.absolutePath
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).start()
        val finished = waitReporting(process, progressFile, progress)
        progressFile.delete()
        if (!finished) process.destroyForcibly()
        val ok = finished && process.exitValue() == 0 && partial.length() > 0
        val outcome = if (ok) "${partial.length() / Http.KB}KB" else "failed: ${log.readText().take(LOG_CHARS)}"
        Diag.log(
            "cast",
            "$label: remuxed the video track of ${source.length() / Http.KB}KB as ${out.extension} " +
                "in ${System.currentTimeMillis() - started}ms -> $outcome",
        )
        log.delete()
        if (!ok) {
            partial.delete()
            return null
        }
        partial.renameTo(out)
        return out
    }

    private fun waitReporting(process: Process, progressFile: File, progress: Progress): Boolean {
        val deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(TIMEOUT_MIN)
        var last = -1
        while (System.currentTimeMillis() < deadline) {
            if (process.waitFor(PROGRESS_EVERY_MS, TimeUnit.MILLISECONDS)) return true
            val done = progressFile.takeIf { it.exists() }?.readLines()
                ?.lastOrNull { it.startsWith("out_time_us=") }?.substringAfter('=')?.toLongOrNull()
            val total = progress.durationUs
            if (done != null && total != null && total > 0) {
                val percent = (done * PERCENT / total).toInt().coerceIn(0, PERCENT - 1)
                if (percent != last) {
                    last = percent
                    progress.report(percent)
                }
            }
        }
        return false
    }

    private const val PROGRESS_EVERY_MS = 500L
    private const val PERCENT = 100
    private const val CACHE_DIR = "cast-remux"
    private const val TIMEOUT_MIN = 10L
    private const val LOG_CHARS = 400
}
