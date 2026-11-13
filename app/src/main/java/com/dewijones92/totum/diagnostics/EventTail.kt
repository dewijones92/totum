package com.dewijones92.totum.diagnostics

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

internal class EventTail(
    private val file: File,
    private val keep: Int = KEEP,
    private val everyMs: Long = SAVE_EVERY_MS,
    private val snapshot: () -> List<Breadcrumbs.Entry> = Breadcrumbs::snapshot,
) {
    @Serializable
    private class Saved(val atEpochMs: Long, val tag: String, val message: String)

    private var lastSavedNewest = Long.MIN_VALUE
    private var lastSavedCount = -1

    fun restore() {
        val saved = try {
            if (!file.isFile) emptyList() else JSON.decodeFromString(LIST, file.readText())
        } catch (e: IOException) {
            Diag.warn("diagnostics", "the event tail from before the restart could not be read", e)
            emptyList()
        } catch (e: kotlinx.serialization.SerializationException) {
            Diag.warn("diagnostics", "the event tail from before the restart is unreadable; dropped", e)
            emptyList()
        }
        if (saved.isEmpty()) {
            Diag.log("diagnostics", "no event tail from before this start (first start, or nothing was saved)")
            return
        }
        Breadcrumbs.restore(saved.map { Breadcrumbs.Entry(it.atEpochMs, it.tag, it.message) })
        Diag.log(
            "diagnostics",
            "restored ${saved.size} event(s) from before this start, marked " +
                "\"${Breadcrumbs.BEFORE_RESTART.trim()}\"; the last was at " +
                Breadcrumbs.formatTime(saved.maxOf { it.atEpochMs }),
        )
    }

    fun save(): Boolean {
        val tail = snapshot().takeLast(keep)
        val newest = tail.maxOfOrNull { it.atEpochMs } ?: return false
        if (newest == lastSavedNewest && tail.size == lastSavedCount) return false
        return try {
            file.parentFile?.mkdirs()
            file.writeTextReplacing(JSON.encodeToString(LIST, tail.map { Saved(it.atEpochMs, it.tag, it.message) }))
            lastSavedNewest = newest
            lastSavedCount = tail.size
            true
        } catch (e: IOException) {
            Diag.warn("diagnostics", "the event tail could not be saved", e)
            false
        }
    }

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(everyMs)
                save()
            }
        }
    }

    private companion object {
        const val KEEP = 300
        const val SAVE_EVERY_MS = 10_000L
        val JSON = Json { ignoreUnknownKeys = true }
        val LIST = ListSerializer(Saved.serializer())
    }
}
