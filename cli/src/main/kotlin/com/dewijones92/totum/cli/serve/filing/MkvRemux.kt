package com.dewijones92.totum.cli.serve.filing

import com.dewijones92.totum.cli.serve.ProcessResult
import com.dewijones92.totum.cli.serve.runProcess
import com.dewijones92.totum.common.Diag
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

sealed interface MkvRemuxOutcome {
    data object NotNeeded : MkvRemuxOutcome
    data class Fixed(val before: Long, val after: Long) : MkvRemuxOutcome
    data class Failed(val why: String) : MkvRemuxOutcome
}

internal class MkvRemux(
    private val ffmpeg: String = "ffmpeg",
    private val run: (List<String>, Long) -> ProcessResult = ::runProcess,
    private val layoutOf: (File) -> MkvLayout = MkvLayout::of,
) {
    fun fixIfNeeded(video: File): MkvRemuxOutcome {
        if (video.extension.lowercase() != "mkv") return MkvRemuxOutcome.NotNeeded
        val layout = layoutOf(video)
        if (layout != MkvLayout.TRACKS_AFTER_CLUSTERS) {
            Diag.log("filing", "${video.name}: matroska layout is $layout; no remux needed")
            return MkvRemuxOutcome.NotNeeded
        }
        val size = video.length()
        val room = video.parentFile.usableSpace
        if (room < size + HEADROOM_BYTES) {
            return failed(video, "its Tracks come late, but only ${room}B are free to remux ${size}B")
        }
        val temp = File(video.parentFile, ".${video.name}$PART_SUFFIX")
        temp.delete()
        Diag.log("filing", "${video.name} (${size}B): Tracks come after the first cluster; remuxing")
        val made = run(
            listOf(ffmpeg, "-nostdin", "-loglevel", "error", "-y", "-i", video.path) +
                listOf("-map", "0", "-c", "copy", "-f", "matroska", temp.path),
            REMUX_MS,
        )
        val after = temp.length()
        val problem = when {
            made.exitCode != 0 -> "ffmpeg exited ${made.exitCode}${if (made.timedOut) " (timed out)" else ""}: " +
                made.stderr.take(LOGGED)
            after < size * MIN_SHARE_PERCENT / PERCENT -> "the remux is ${after}B against ${size}B"
            layoutOf(temp) != MkvLayout.TRACKS_FIRST -> "the remux still does not put Tracks first"
            else -> null
        }
        if (problem != null) {
            temp.delete()
            return failed(video, problem)
        }
        return try {
            Files.move(
                temp.toPath(),
                video.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Diag.log("filing", "${video.name}: remuxed so Tracks come first (${size}B -> ${after}B)")
            MkvRemuxOutcome.Fixed(size, after)
        } catch (e: IOException) {
            temp.delete()
            failed(video, "the remuxed copy could not replace the original: ${e.message}")
        }
    }

    private fun failed(video: File, why: String): MkvRemuxOutcome {
        Diag.warn("filing", "${video.name}: not remuxed, the original is kept: $why")
        return MkvRemuxOutcome.Failed(why)
    }

    companion object {
        const val PART_SUFFIX = ".remux.part"
        private const val REMUX_MS = 30 * 60_000L
        private const val HEADROOM_BYTES = 1_000_000_000L
        private const val MIN_SHARE_PERCENT = 90L
        private const val PERCENT = 100L
        private const val LOGGED = 300
    }
}
