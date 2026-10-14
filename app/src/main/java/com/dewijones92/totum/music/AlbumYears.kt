package com.dewijones92.totum.music

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.innertube.music.MusicResult
import com.dewijones92.totum.innertube.music.YouTubeMusicCatalogue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class AlbumYears(
    private val catalogue: YouTubeMusicCatalogue,
    private val scope: CoroutineScope,
    private val file: File?,
) {
    private val mutableKnown = MutableStateFlow(load())
    val known: StateFlow<Map<String, String>> = mutableKnown.asStateFlow()
    private val asked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val lanes = Semaphore(LANES)
    private val writing = Mutex()

    fun yearOf(albumId: String): String? = known.value[albumId]?.ifEmpty { null }

    fun want(albumId: String) {
        if (albumId in known.value || !asked.add(albumId)) return
        scope.launch {
            lanes.withPermit {
                when (val result = catalogue.album(albumId)) {
                    is MusicResult.Success -> {
                        val year = result.value.year?.trim().orEmpty()
                        Diag.log(
                            "music",
                            "album $albumId \"${result.value.title}\" is from ${year.ifEmpty { "no stated year" }}"
                        )
                        mutableKnown.update { it + (albumId to year) }
                        save()
                    }
                    is MusicResult.Failure -> {
                        asked.remove(albumId)
                        Diag.warn("music", "album $albumId year not found: ${result.detail}")
                    }
                }
            }
        }
    }

    private fun load(): Map<String, String> = runCatching {
        file?.takeIf { it.exists() }?.readLines()?.mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 }?.let { (id, year) -> id to year }
        }?.toMap().orEmpty()
    }.getOrElse {
        Diag.warn("music", "album years unreadable, starting afresh: $it")
        emptyMap()
    }

    private suspend fun save() {
        val file = file ?: return
        writing.withLock {
            runCatching {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    file.writeText(known.value.entries.joinToString("") { (id, year) -> "$id\t$year\n" })
                }
            }.onFailure { Diag.warn("music", "album years not saved: $it") }
        }
    }

    private companion object {
        const val LANES = 2
    }
}
