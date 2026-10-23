package com.dewijones92.totum.data.download

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItemId
import java.io.File

internal class DownloadNotes(private val file: File?) {
    private val heights = mutableMapOf<String, Int>()
    private val resumes = mutableMapOf<String, Int>()

    init {
        runCatching {
            file?.takeIf { it.exists() }?.readLines()?.forEach { line ->
                val parts = line.split('\t')
                val value = parts.getOrNull(2)?.toIntOrNull() ?: return@forEach
                when (parts[0]) {
                    HEIGHT -> heights[parts[1]] = value
                    RESUMES -> resumes[parts[1]] = value
                }
            }
        }.onFailure { Diag.warn("download", "download notes unreadable, starting afresh: $it") }
    }

    @Synchronized
    fun height(id: MediaItemId): Int? = heights[id.value]

    @Synchronized
    fun asked(id: MediaItemId, maxHeight: Int?) {
        if (maxHeight == null) heights.remove(id.value) else heights[id.value] = maxHeight
        save()
    }

    @Synchronized
    fun resumedOnceMore(id: MediaItemId): Int = (resumes.getOrDefault(id.value, 0) + 1).also {
        resumes[id.value] = it
        save()
    }

    @Synchronized
    fun settled(id: MediaItemId) {
        if (resumes.remove(id.value) != null) save()
    }

    @Synchronized
    fun forget(id: MediaItemId) {
        val hadHeight = heights.remove(id.value) != null
        val hadResumes = resumes.remove(id.value) != null
        if (hadHeight || hadResumes) save()
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeText(
                heights.entries.joinToString("") { (id, h) -> "$HEIGHT\t$id\t$h\n" } +
                    resumes.entries.joinToString("") { (id, n) -> "$RESUMES\t$id\t$n\n" },
            )
        }.onFailure { Diag.warn("download", "download notes not saved: $it") }
    }

    private companion object {
        const val HEIGHT = "height"
        const val RESUMES = "resumes"
    }
}
