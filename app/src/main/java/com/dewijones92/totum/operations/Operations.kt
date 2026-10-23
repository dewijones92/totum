package com.dewijones92.totum.operations

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class OperationKind(val toastsResult: Boolean = true) {
    SHUFFLE_ARTIST,
    SHUFFLE_SOURCES,
    OPEN_PAGE(toastsResult = false),
    PREPARE_TORRENT,
    DOWNLOAD_BATCH,
}

data class Operation(
    val id: Long,
    val key: String,
    val kind: OperationKind,
    val subject: String,
    val found: Int = 0,
    val step: Int? = null,
    val steps: Int? = null,
    val playing: Boolean = false,
    val startedAtMs: Long = 0,
)

sealed interface OperationEnd {
    data class Done(val count: Int, val partial: Boolean = false) : OperationEnd
    data object Nothing : OperationEnd
    data class Failed(val why: String) : OperationEnd
    data class Cancelled(val playing: Boolean) : OperationEnd
}

data class Ended(val kind: OperationKind, val subject: String, val end: OperationEnd, val tookMs: Long)

class Operations(private val clock: () -> Long = System::currentTimeMillis) {
    private val live = MutableStateFlow<List<Operation>>(emptyList())
    val running: StateFlow<List<Operation>> = live.asStateFlow()

    private val finished = MutableSharedFlow<Ended>(extraBufferCapacity = ENDED_BUFFER)
    val ended: SharedFlow<Ended> = finished.asSharedFlow()

    private val jobs = ConcurrentHashMap<Long, Job>()
    private val numbers = AtomicLong()

    fun isRunning(key: String): Boolean = live.value.any { it.key == key }

    @Suppress("TooGenericExceptionCaught")
    suspend fun track(
        key: String,
        kind: OperationKind,
        subject: String,
        block: suspend (Progress) -> OperationEnd,
    ): OperationEnd {
        val id = numbers.incrementAndGet()
        val started = clock()
        live.update { it + Operation(id, key, kind, subject, startedAtMs = started) }
        jobs[id] = currentCoroutineContext().job
        Diag.log(TAG, "#$id started: $kind \"$subject\"")
        val progress = Progress(id)
        var end: OperationEnd? = null
        try {
            end = block(progress)
        } catch (cancelled: CancellationException) {
            end = OperationEnd.Cancelled(progress.playing())
            throw cancelled
        } catch (failure: Exception) {
            Diag.warn(TAG, "#$id $kind \"$subject\" failed", failure)
            end = OperationEnd.Failed(failure.message ?: failure.javaClass.simpleName)
        } finally {
            finish(id, kind, subject, end ?: OperationEnd.Failed("stopped by an error"), started)
        }
        return end
    }

    fun cancel(id: Long) {
        val job = jobs[id]
        Diag.log(TAG, "#$id cancel asked for${if (job == null) ", but it has already ended" else ""}")
        job?.cancel(CancellationException("cancelled from the progress strip"))
    }

    private fun finish(id: Long, kind: OperationKind, subject: String, end: OperationEnd, started: Long) {
        jobs.remove(id)
        live.update { list -> list.filterNot { it.id == id } }
        val took = clock() - started
        Diag.log(TAG, "#$id ended after ${took}ms: $kind \"$subject\" -> $end")
        finished.tryEmit(Ended(kind, subject, end, took))
    }

    inner class Progress internal constructor(private val id: Long) {
        fun report(found: Int? = null, step: Int? = null, steps: Int? = null, playing: Boolean? = null) {
            live.update { list ->
                list.map { op ->
                    if (op.id != id) {
                        op
                    } else {
                        op.copy(
                            found = found ?: op.found,
                            step = step ?: op.step,
                            steps = steps ?: op.steps,
                            playing = playing ?: op.playing,
                        )
                    }
                }
            }
        }

        fun playing(): Boolean = live.value.firstOrNull { it.id == id }?.playing == true
    }

    private companion object {
        const val TAG = "ops"
        const val ENDED_BUFFER = 16
    }
}

suspend fun Operations?.tracking(
    key: String,
    kind: OperationKind,
    subject: String,
    block: suspend (Operations.Progress?) -> OperationEnd,
): OperationEnd = this?.track(key, kind, subject) { block(it) } ?: block(null)
