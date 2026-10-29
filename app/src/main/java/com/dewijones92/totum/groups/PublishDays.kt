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
        val known = lock.withLock { loaded()[id.value] }
        if (known != null) return if (known == NONE) null else runCatching { LocalDate.parse(known) }.getOrNull()
        val day = ask(id)
        Diag.log(TAG, "publish day of ${id.value}: ${day ?: "not given"} (asked YouTube once; kept for good)")
        remember(id, day?.toString() ?: NONE)
        return day
    }

    private suspend fun remember(id: MediaItemId, value: String) = lock.withLock {
        val all = loaded()
        all[id.value] = value
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json.encodeToString(serializer, all))
                check(temp.renameTo(file)) { "rename failed" }
            }.onFailure { Diag.warn(TAG, "publish days could not be saved", it) }
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
        private const val NONE = "-"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        fun asking(client: InnerTubeClient): suspend (MediaItemId) -> LocalDate? = { id ->
            (client.next(id.value) as? InnerTubeResponse.Success)?.parsed(WatchNextParser::publishedOn)
        }
    }
}
