package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
public data class SeenFile(public val index: Int, public val label: String)

@Serializable
public data class SeenTorrent(
    public val hash: String,
    public val name: String,
    public val files: List<SeenFile>,
    public val atEpochMs: Long,
)

public class TorrentsSeen(
    private val file: File,
    private val keep: Int = KEEP,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val all = MutableStateFlow<Map<String, SeenTorrent>>(emptyMap())
    private var loaded = false

    public val records: StateFlow<Map<String, SeenTorrent>> = all.asStateFlow()

    public suspend fun load(): Unit = lock.withLock { loadOnce() }

    public suspend fun remember(torrent: PreparedTorrent): Unit = lock.withLock {
        loadOnce()
        val files = TorrentEpisodes.playableInOrder(torrent.files).map { SeenFile(it.index, TorrentEpisodes.label(it)) }
        val hash = torrent.hash.lowercase()
        val record = SeenTorrent(hash, torrent.name, files, clock())
        val kept = (all.value - hash).values.sortedByDescending { it.atEpochMs }.take(keep - 1)
        all.value = (kept + record).associateBy { it.hash }
        write(all.value.values.toList())
        Diag.log(
            "torrent",
            "remembered \"${torrent.name}\" ($hash): ${files.size} playable file(s); ${all.value.size} kept"
        )
    }

    private suspend fun loadOnce() {
        if (loaded) return
        all.value = withContext(Dispatchers.IO) {
            runCatching {
                if (!file.exists()) {
                    emptyList()
                } else {
                    JSON.decodeFromString(LIST, file.readText())
                }
            }.getOrElse { e ->
                Diag.warn("torrent", "the torrents-seen file could not be read; starting afresh", e)
                emptyList()
            }
        }.associateBy { it.hash }
        loaded = true
        Diag.log("torrent", "torrents seen: ${all.value.size} loaded")
    }

    private suspend fun write(records: List<SeenTorrent>) = withContext(Dispatchers.IO) {
        try {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(JSON.encodeToString(LIST, records))
            if (!temp.renameTo(file)) throw IOException("could not replace ${file.name}")
        } catch (e: IOException) {
            Diag.warn("torrent", "the torrents-seen file could not be written", e)
        }
    }

    private companion object {
        const val KEEP = 500
        val JSON = Json { ignoreUnknownKeys = true }
        val LIST = ListSerializer(SeenTorrent.serializer())
    }
}
