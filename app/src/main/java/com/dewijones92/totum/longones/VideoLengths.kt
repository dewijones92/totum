package com.dewijones92.totum.longones

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.kept.KeptFacts
import com.dewijones92.totum.video.LengthCheck
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class VideoLengths(
    file: File,
    private val ask: suspend (MediaItem) -> LengthCheck,
    private val now: () -> Long = System::currentTimeMillis,
) {
    sealed interface Looked {
        data class Known(val length: Duration, val asked: Boolean, val endedStream: Boolean = false) : Looked
        data class NotNow(val why: String, val reason: Reason, val asked: Boolean) : Looked
    }

    enum class Reason { LIVE_OR_UPCOMING, NO_LENGTH, UNKNOWN }

    private val kept = KeptFacts(file, "video lengths", TAG)
    private val setAside = ConcurrentHashMap<String, SetAside>()

    private data class SetAside(val until: Long, val why: String, val reason: Reason)

    suspend fun cached(id: String): Duration? = kept.get(id)?.toLongOrNull()?.takeIf { it > 0 }?.seconds

    suspend fun keep(lengths: Map<String, Duration>) {
        val fresh = lengths.filter { (id, length) -> length.isPositive() && kept.get(id) == null }
        if (fresh.isEmpty()) return
        kept.put(fresh.mapValues { it.value.inWholeSeconds.toString() })
        Diag.log(TAG, "dewidebug kept ${fresh.size} length(s) the feeds already said; ${kept.size()} kept in all")
    }

    suspend fun lookUp(item: MediaItem): Looked {
        val id = item.id.value
        cached(id)?.let { return Looked.Known(it, asked = false) }
        setAside[id]?.takeIf { it.until > now() }?.let { return Looked.NotNow(it.why, it.reason, asked = false) }
        val started = now()
        val answer = runCatching { ask(item) }.getOrElse { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            Diag.warn(TAG, "dewidebug length of $id: the look-up threw", failure)
            LengthCheck.Unknown("threw ${failure.javaClass.simpleName}")
        }
        val took = now() - started
        return when (answer) {
            is LengthCheck.Known -> Looked.Known(
                answer.seconds.seconds,
                asked = true,
                endedStream = answer.wasLive
            ).also {
                kept.put(mapOf(id to answer.seconds.toString()))
                val said = "${answer.seconds}s via ${answer.via} in ${took}ms" +
                    if (answer.wasLive) " (a stream that ended)" else ""
                Diag.log(TAG, "dewidebug length of $id: $said (kept for good)")
            }
            is LengthCheck.LiveNow -> notNow(id, "🔴 live now", Reason.LIVE_OR_UPCOMING, LIVE_HOLD_MS, took)
            is LengthCheck.Upcoming -> notNow(id, "⏳ not started yet", Reason.LIVE_OR_UPCOMING, LIVE_HOLD_MS, took)
            is LengthCheck.SaysNone -> notNow(id, "❓ ${answer.why}", Reason.NO_LENGTH, NONE_HOLD_MS, took)
            is LengthCheck.Unknown -> notNow(id, "❓ ${answer.why}", Reason.UNKNOWN, UNKNOWN_HOLD_MS, took)
        }
    }

    private fun notNow(id: String, why: String, reason: Reason, holdMs: Long, took: Long): Looked.NotNow {
        setAside[id] = SetAside(now() + holdMs, why, reason)
        Diag.log(TAG, "dewidebug length of $id: $why after ${took}ms; not asked again for ${holdMs / MINUTE_MS} min")
        return Looked.NotNow(why, reason, asked = true)
    }

    companion object {
        private const val TAG = "longones"
        private const val MINUTE_MS = 60_000L
        private const val LIVE_HOLD_MS = 60 * MINUTE_MS
        private const val NONE_HOLD_MS = 6 * 60 * MINUTE_MS
        private const val UNKNOWN_HOLD_MS = 15 * MINUTE_MS
    }
}
