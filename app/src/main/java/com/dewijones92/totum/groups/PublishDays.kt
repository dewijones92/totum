package com.dewijones92.totum.groups

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import com.dewijones92.totum.innertube.browse.InnerTubeResponse
import com.dewijones92.totum.innertube.browse.parsed
import com.dewijones92.totum.innertube.player.WatchNextParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

class PublishDays(
    private val file: File,
    private val ask: suspend (MediaItemId) -> LocalDate?,
) {
    private val lock = Mutex()
    private var days: MutableMap<String, String>? = null

    suspend fun dayOf(id: MediaItemId): LocalDate? {
        known(id)?.let { return it }
        val day = runCatching { ask(id) }
            .onFailure { Diag.warn(TAG, "publish day of ${id.value} could not be asked", it) }
            .getOrNull()
        Diag.log(TAG, "publish day of ${id.value}: ${day ?: "not found"} (asked YouTube once; kept for good)")
        if (day != null) remember(id, day)
        return day
    }

    private suspend fun known(id: MediaItemId): LocalDate? = lock.withLock {
        loaded()[id.value]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    }

    private suspend fun remember(id: MediaItemId, day: LocalDate) = lock.withLock {
        val all = loaded()
        all[id.value] = day.toString()
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(serializer, all)) }
                .onFailure { Diag.warn(TAG, "publish days could not be saved", it) }
        }
    }

    private suspend fun loaded(): MutableMap<String, String> = days ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }
            .getOrElse {
                if (file.exists()) Diag.warn(TAG, "publish days unreadable; starting again", it)
                mutableMapOf()
            }
    }.also { days = it }

    companion object {
        private const val TAG = "group"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        fun asking(client: InnerTubeClient): suspend (MediaItemId) -> LocalDate? = { id ->
            (client.next(id.value) as? InnerTubeResponse.Success)?.parsed(WatchNextParser::publishedOn)
        }
    }
}
