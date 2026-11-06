package com.dewijones92.totum.video

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

interface LiveStatesStore {
    fun load(): List<LiveState>

    fun save(states: Collection<LiveState>)
}

class FileLiveStatesStore(private val file: File) : LiveStatesStore {
    @Serializable
    private data class Row(
        val videoId: String,
        val phase: String,
        val startsAtMs: Long? = null,
        val said: String = "",
        val firstSeenMs: Long,
        val lastSeenMs: Long,
    )

    override fun load(): List<LiveState> = runCatching {
        if (!file.exists()) {
            emptyList()
        } else {
            JSON.decodeFromString(ROWS, file.readText()).mapNotNull { row ->
                val phase = LivePhase.entries.firstOrNull { it.name == row.phase } ?: return@mapNotNull null
                LiveState(
                    row.videoId,
                    phase,
                    row.startsAtMs?.let(Instant::ofEpochMilli),
                    row.said,
                    row.firstSeenMs,
                    row.lastSeenMs,
                )
            }
        }
    }.getOrElse {
        Diag.warn("live", "dewidebug the saved live entries are unreadable, starting with none: $it")
        emptyList()
    }

    override fun save(states: Collection<LiveState>) {
        val rows = states.map {
            Row(it.videoId, it.phase.name, it.startsAt?.toEpochMilli(), it.said, it.firstSeenMs, it.lastSeenMs)
        }
        runCatching { file.writeTextReplacing(JSON.encodeToString(ROWS, rows)) }
            .onFailure { Diag.warn("live", "dewidebug the live entries were not saved: $it") }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val ROWS = ListSerializer(Row.serializer())
    }
}
