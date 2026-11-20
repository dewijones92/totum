package com.dewijones92.totum.groups

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed
import com.dewijones92.totum.innertube.player.WatchNextParser
import com.dewijones92.totum.kept.KeptFacts
import java.io.File
import java.time.LocalDate

class PublishDays(
    file: File,
    private val ask: suspend (MediaItemId) -> Answer,
) {
    sealed interface Answer {
        data class Day(val day: LocalDate) : Answer
        data object NoDay : Answer
        data class Failed(val why: String) : Answer
    }

    private val kept = KeptFacts(file, "publish days", TAG)

    suspend fun dayOf(id: MediaItemId): LocalDate? {
        val known = kept.get(id.value)
        if (known != null) return if (known == NONE) null else runCatching { LocalDate.parse(known) }.getOrNull()
        return when (val answer = ask(id)) {
            is Answer.Day -> answer.day.also {
                Diag.log(TAG, "publish day of ${id.value}: $it (asked YouTube once; kept for good)")
                remember(id, it.toString())
            }
            Answer.NoDay -> null.also {
                Diag.log(TAG, "publish day of ${id.value}: the page gives none (kept, not asked again)")
                remember(id, NONE)
            }
            is Answer.Failed -> null.also {
                Diag.warn(TAG, "publish day of ${id.value}: lookup failed (${answer.why}); asked again next time")
            }
        }
    }

    private suspend fun remember(id: MediaItemId, value: String) = kept.put(mapOf(id.value to value))

    companion object {
        private const val TAG = "group"
        private const val NONE = "-"
        private const val FAILURE_CHARS = 120

        fun asking(client: InnerTubeClient): suspend (MediaItemId) -> Answer = { id ->
            when (val response = client.next(id.value)) {
                is InnerTubeResponse.Success ->
                    response.parsed(WatchNextParser::publishedOn)?.let { Answer.Day(it) } ?: Answer.NoDay
                else -> Answer.Failed(response.toString().take(FAILURE_CHARS))
            }
        }
    }
}
