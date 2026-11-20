package com.dewijones92.totum.kept

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

class KeptFacts(private val file: File, private val what: String, private val tag: String) {
    private val lock = Mutex()
    private var facts: MutableMap<String, String>? = null

    suspend fun get(key: String): String? = lock.withLock { loaded()[key] }

    suspend fun size(): Int = lock.withLock { loaded().size }

    suspend fun put(entries: Map<String, String>) = lock.withLock {
        if (entries.isEmpty()) return@withLock
        val all = loaded()
        all.putAll(entries)
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(json.encodeToString(serializer, all))
                check(temp.renameTo(file)) { "rename failed" }
            }.onFailure { Diag.warn(tag, "$what could not be saved", it) }
        }
    }

    private suspend fun loaded(): MutableMap<String, String> = facts ?: withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(serializer, file.readText()).toMutableMap() }
            .getOrElse {
                if (file.exists()) Diag.warn(tag, "$what unreadable; starting again", it)
                mutableMapOf()
            }
    }.also { facts = it }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), String.serializer())
    }
}
