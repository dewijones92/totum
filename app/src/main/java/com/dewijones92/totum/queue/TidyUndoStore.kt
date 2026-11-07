package com.dewijones92.totum.queue

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.writeTextReplacing
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetsJson
import kotlinx.serialization.Serializable
import java.io.File

class TidyUndoStore(
    private val file: File? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = UNDO_WINDOW_MS,
) {
    @Serializable
    data class Held(val index: Int, val entry: TargetEntry)

    @Serializable
    data class Batch(val atMs: Long, val held: List<Held>)

    @Volatile
    private var memory: Batch? = null

    fun hold(held: List<Held>) {
        val batch = Batch(now(), held)
        memory = batch
        file?.let { target ->
            runCatching { target.writeTextReplacing(TargetsJson.encodeToString(Batch.serializer(), batch)) }
                .onFailure { Diag.warn(TAG, "dewidebug the tidy-up undo could not be saved: $it") }
        }
        Diag.log(TAG, "dewidebug holding ${held.size} tidied item(s) for an Undo for ${windowMs / MS_PER_MINUTE} min")
    }

    fun take(why: String): Batch? {
        val batch = memory ?: readFile()
        clear("taken for $why")
        if (batch == null) {
            Diag.log(TAG, "dewidebug undo ($why): nothing is held, so nothing to put back")
            return null
        }
        val age = now() - batch.atMs
        if (age > windowMs) {
            Diag.log(TAG, "dewidebug undo ($why): the held batch is ${age / MS_PER_MINUTE} min old, past its window")
            return null
        }
        return batch
    }

    fun clear(why: String) {
        memory = null
        file?.let { if (it.exists() && !it.delete()) Diag.warn(TAG, "dewidebug the tidy-up undo file would not go") }
        Diag.log(TAG, "dewidebug tidy-up undo cleared ($why)")
    }

    private fun readFile(): Batch? = file?.takeIf { it.exists() }?.let { target ->
        runCatching { TargetsJson.decodeFromString(Batch.serializer(), target.readText()) }
            .onFailure { Diag.warn(TAG, "dewidebug the held tidy-up is unreadable: $it") }
            .getOrNull()
    }

    companion object {
        const val UNDO_WINDOW_MS = 6L * 60 * 60 * 1000
        private const val MS_PER_MINUTE = 60_000L
        private const val TAG = "tidy"
    }
}
