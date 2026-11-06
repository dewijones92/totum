package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeProblem
import com.dewijones92.totum.data.home.HomeSeverity

internal class HomeHealth(
    private val probes: Probes,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lowFreeBytes: Long = LOW_FREE_BYTES,
    private val stalledMs: Long = STALLED_MS,
) {
    class Probes(
        val freeBytes: () -> Long,
        val ai: suspend () -> AiState,
        val jellyfin: (suspend () -> Boolean)? = null,
    )

    enum class AiState { SIGNED_IN, NOT_SIGNED_IN, UNKNOWN, UNREACHABLE, LOCAL }

    private val moved = mutableMapOf<String, Pair<Long, Long>>()
    private var said = emptySet<String>()

    @Synchronized
    fun noteProgress(jobs: List<HomeJob>) {
        val now = clock()
        val running = jobs.filter { it.state == HomeJobState.DOWNLOADING }
        moved.keys.retainAll(running.map { it.id }.toSet())
        running.forEach { job ->
            val before = moved[job.id]
            if (before == null || before.first != job.bytes) moved[job.id] = job.bytes to now
        }
    }

    suspend fun problems(jobs: List<HomeJob>): List<HomeProblem> {
        noteProgress(jobs)
        val found = buildList {
            val free = probes.freeBytes()
            if (free < lowFreeBytes) {
                add(HomeProblem("disk", HomeSeverity.WARNING, "⚠️ Only ${free / GB} GB free on the Pi"))
            }
            when (probes.ai()) {
                AiState.UNREACHABLE -> add(
                    HomeProblem(
                        "ai",
                        HomeSeverity.BROKEN,
                        "❌ The AI service on the Pi is not answering; ✨ Ask is off and filing uses rules"
                    ),
                )
                AiState.NOT_SIGNED_IN -> add(
                    HomeProblem(
                        "ai-sign-in",
                        HomeSeverity.WARNING,
                        "⚠️ The home server isn't signed in to Claude; ✨ Ask is off and filing uses rules"
                    ),
                )
                else -> Unit
            }
            if (probes.jellyfin?.invoke() == false) {
                add(
                    HomeProblem(
                        "jellyfin",
                        HomeSeverity.WARNING,
                        "⚠️ Jellyfin is not answering, so new films are not added to it"
                    )
                )
            }
            addAll(stalled(jobs))
        }
        val keys = found.map { it.key }.toSet()
        if (keys != said) Diag.log("health", "home problems now: ${found.joinToString { it.text }.ifEmpty { "none" }}")
        said = keys
        return found
    }

    @Synchronized
    private fun stalled(jobs: List<HomeJob>): List<HomeProblem> {
        val now = clock()
        return jobs.filter { it.state == HomeJobState.DOWNLOADING }.mapNotNull { job ->
            val since = moved[job.id]?.second ?: return@mapNotNull null
            val quiet = now - since
            if (quiet < stalledMs) return@mapNotNull null
            HomeProblem(
                "stalled:${job.id}",
                HomeSeverity.WARNING,
                "⏳ \"${job.item.title}\" has not moved for ${quiet / MINUTE} min"
            )
        }
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val LOW_FREE_BYTES = 30 * GB
        const val MINUTE = 60_000L
        const val STALLED_MS = 10 * MINUTE
    }
}
