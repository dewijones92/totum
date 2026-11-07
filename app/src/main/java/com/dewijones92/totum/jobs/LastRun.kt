package com.dewijones92.totum.jobs

import android.content.SharedPreferences
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class LastRun(
    val ranAtMs: Long,
    val succeeded: Boolean,
    val detail: String,
    val lastSucceededAtMs: Long?,
) {
    fun encoded(): String = listOf(ranAtMs, succeeded, lastSucceededAtMs ?: NEVER, detail).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "|"
        private const val NEVER = -1L
        private const val FIELDS = 4
        private const val DETAIL = FIELDS - 1

        fun decoded(text: String): LastRun? {
            val parts = text.split(SEPARATOR, limit = FIELDS)
            if (parts.size != FIELDS) return null
            val ran = parts[0].toLongOrNull() ?: return null
            val ok = parts[1].toBooleanStrictOrNull() ?: return null
            val lastOk = parts[2].toLongOrNull() ?: return null
            return LastRun(ran, ok, parts[DETAIL], lastOk.takeIf { it != NEVER })
        }
    }
}

interface LastRunStore {
    fun all(): Map<String, String>

    fun put(name: String, encoded: String)
}

class InMemoryLastRunStore : LastRunStore {
    private val held = mutableMapOf<String, String>()

    override fun all(): Map<String, String> = held.toMap()

    override fun put(name: String, encoded: String) {
        held[name] = encoded
    }
}

class SharedPrefsLastRunStore(private val prefs: SharedPreferences) : LastRunStore {
    override fun all(): Map<String, String> = prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    override fun put(name: String, encoded: String) {
        prefs.edit { putString(name, encoded) }
    }
}

class LastRuns(
    private val store: LastRunStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val all = MutableStateFlow(
        store.all().mapNotNull { (name, value) -> LastRun.decoded(value)?.let { name to it } }.toMap(),
    )

    fun get(name: String): LastRun? = all.value[name]

    fun snapshot(): Map<String, LastRun> = all.value

    fun observe(name: String): Flow<LastRun?> = all.map { it[name] }.distinctUntilChanged()

    fun succeeded(name: String, detail: String = "") = record(name, true, detail)

    fun failed(name: String, reason: String) = record(name, false, reason)

    private fun record(name: String, succeeded: Boolean, detail: String) {
        val at = now()
        val before = all.value[name]
        val run = LastRun(at, succeeded, detail, if (succeeded) at else before?.lastSucceededAtMs)
        store.put(name, run.encoded())
        all.update { it + (name to run) }
        Diag.log(
            "jobs",
            "dewidebug $name ${if (succeeded) "succeeded" else "FAILED"}: ${detail.ifEmpty { "(no detail)" }} " +
                "[previous=${before?.let { if (it.succeeded) "ok" else "failed" } ?: "never ran"} " +
                "lastSucceeded=${run.lastSucceededAtMs ?: "never"}]",
        )
    }
}

sealed interface Ago {
    data object JustNow : Ago

    data class Minutes(val count: Int) : Ago

    data class Hours(val count: Int) : Ago

    data class Days(val count: Int) : Ago
}

private const val MS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L

fun agoOf(thenMs: Long, nowMs: Long): Ago {
    val minutes = ((nowMs - thenMs) / MS_PER_MINUTE).coerceAtLeast(0)
    return when {
        minutes < 1 -> Ago.JustNow
        minutes < MINUTES_PER_HOUR -> Ago.Minutes(minutes.toInt())
        minutes < MINUTES_PER_HOUR * HOURS_PER_DAY -> Ago.Hours((minutes / MINUTES_PER_HOUR).toInt())
        else -> Ago.Days((minutes / (MINUTES_PER_HOUR * HOURS_PER_DAY)).toInt())
    }
}
