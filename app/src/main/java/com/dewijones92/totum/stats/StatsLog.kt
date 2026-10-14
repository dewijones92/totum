package com.dewijones92.totum.stats

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
open class StatsLog(
    private val dir: File?,
    private val scope: CoroutineScope,
    private val writer: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) {
    private val memory = mutableListOf<StatEvent>()

    open fun record(event: StatEvent) {
        scope.launch(writer) {
            val file = dir?.let {
                File(it, "events-${YearMonth.from(Instant.ofEpochMilli(event.at).atZone(ZoneOffset.UTC))}.jsonl")
            }
            if (file == null) {
                memory += event
                return@launch
            }
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(StatsCodec.line(event) + "\n")
            }.onFailure { Diag.warn(TAG, "dewidebug stats could not record ${event::class.simpleName}", it) }
        }
    }

    fun use(what: UseKind, detail: String? = null, bytes: Long? = null) =
        record(StatEvent.AppUse(System.currentTimeMillis(), what, bytes, detail))

    fun counting(what: UseKind, action: () -> Unit): () -> Unit = {
        action()
        use(what)
    }

    suspend fun all(): List<StatEvent> = withContext(writer) {
        val files = dir?.listFiles { f -> f.name.startsWith("events-") && f.name.endsWith(".jsonl") }.orEmpty()
        val decoded = files.sortedBy { it.name }.map { file -> file.useLines { StatsCodec.read(it) } }
        val events = decoded.flatMap { it.events } + memory
        val unreadable = decoded.sumOf { it.unreadable }
        Diag.log(TAG, "stats read ${events.size} event(s) from ${files.size} file(s); unreadable lines: $unreadable")
        events.sortedBy { it.at }
    }

    suspend fun pending(): String? = withContext(writer) {
        dir?.let { File(it, PENDING) }?.takeIf { it.exists() }?.readText()
    }

    fun savePending(text: String?) {
        scope.launch(writer) {
            val file = dir?.let { File(it, PENDING) } ?: return@launch
            runCatching {
                if (text == null) {
                    file.delete()
                } else {
                    file.parentFile?.mkdirs()
                    val next = File(file.parentFile, "$PENDING.new")
                    next.writeText(text)
                    if (!next.renameTo(
                            file
                        )
                    ) {
                        Diag.warn(TAG, "dewidebug stats could not move the play in progress into place")
                    }
                }
            }.onFailure { Diag.warn(TAG, "dewidebug stats could not save the play in progress", it) }
        }
    }

    companion object {
        const val TAG = "stats"
        private const val PENDING = "pending-listen.json"
    }
}
