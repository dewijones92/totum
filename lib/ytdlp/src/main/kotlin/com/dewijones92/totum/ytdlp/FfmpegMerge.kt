package com.dewijones92.totum.ytdlp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

public object FfmpegMerge {

    public suspend fun merge(ffmpeg: String, video: File, audio: File, output: File): String? =
        withContext(Dispatchers.IO) {
            val log = File(output.parentFile, "${output.name}.ffmpeg.log")
            val process = ProcessBuilder(
                ffmpeg, "-y", "-nostdin", "-loglevel", "error",
                "-i", video.absolutePath, "-i", audio.absolutePath,
                "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "matroska", output.absolutePath,
            ).redirectErrorStream(true).redirectOutput(log).start()
            val finished = process.waitFor(MERGE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            val said = log.takeIf { it.exists() }?.readText()?.take(MERGE_LOG_CHARS).orEmpty().also { log.delete() }
            when {
                !finished -> "ffmpeg did not finish in $MERGE_TIMEOUT_MINUTES min".also { process.destroyForcibly() }
                process.exitValue() != 0 -> "ffmpeg exited ${process.exitValue()}: $said"
                !output.exists() || output.length() == 0L -> "ffmpeg wrote nothing"
                else -> null
            }
        }

    private const val MERGE_TIMEOUT_MINUTES = 10L
    private const val MERGE_LOG_CHARS = 400
}
