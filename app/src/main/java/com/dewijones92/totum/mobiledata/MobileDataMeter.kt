package com.dewijones92.totum.mobiledata

import android.content.SharedPreferences
import android.net.TrafficStats
import android.os.Process
import androidx.core.content.edit
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class MobileDataState(val day: LocalDate, val bytes: Long, val metered: Boolean)

fun MobileDataState.reachedLimit(limitMb: Int): Boolean = limitMb > 0 && bytes >= limitMb * BYTES_PER_MB

fun MobileDataState.holdingBack(limitMb: Int): Boolean = metered && reachedLimit(limitMb)

const val BYTES_PER_MB = 1_048_576L

fun appUidBytes(): Long? {
    val uid = Process.myUid()
    val received = TrafficStats.getUidRxBytes(uid)
    val sent = TrafficStats.getUidTxBytes(uid)
    return if (received == TrafficStats.UNSUPPORTED.toLong() || sent == TrafficStats.UNSUPPORTED.toLong()) {
        null
    } else {
        received + sent
    }
}

data class MobileDataSaved(val day: LocalDate?, val bytes: Long, val counter: Long?)

interface MobileDataStore {
    fun load(): MobileDataSaved

    fun save(saved: MobileDataSaved)
}

class InMemoryMobileDataStore : MobileDataStore {
    private var held = MobileDataSaved(null, 0L, null)

    override fun load(): MobileDataSaved = held

    override fun save(saved: MobileDataSaved) {
        held = saved
    }
}

class SharedPrefsMobileDataStore(private val prefs: SharedPreferences) : MobileDataStore {
    override fun load(): MobileDataSaved = MobileDataSaved(
        day = prefs.getString(KEY_DAY, null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        bytes = prefs.getLong(KEY_BYTES, 0L),
        counter = prefs.getLong(KEY_COUNTER, NO_COUNTER).takeIf { it != NO_COUNTER },
    )

    override fun save(saved: MobileDataSaved) {
        prefs.edit {
            putString(KEY_DAY, saved.day.toString())
            putLong(KEY_BYTES, saved.bytes)
            putLong(KEY_COUNTER, saved.counter ?: NO_COUNTER)
        }
    }

    private companion object {
        const val KEY_DAY = "day"
        const val KEY_BYTES = "bytes"
        const val KEY_COUNTER = "counter"
        const val NO_COUNTER = -1L
    }
}

class MobileDataMeter(
    private val store: MobileDataStore,
    private val scope: CoroutineScope,
    private val uidBytes: () -> Long? = ::appUidBytes,
    private val metered: () -> Boolean,
    private val limitMb: () -> Int,
    private val today: () -> LocalDate = LocalDate::now,
    private val sampleEveryMs: Long = SAMPLE_MS,
) {
    private val saved = store.load()
    private val mutableState = MutableStateFlow(MobileDataState(saved.day ?: today(), saved.bytes, metered = false))
    val state: StateFlow<MobileDataState> = mutableState.asStateFlow()

    private var lastCounter: Long? = saved.counter
    private var loggedAtBytes = mutableState.value.bytes
    private var unsupportedSaid = false

    fun start(): Job {
        Diag.log(
            "mobiledata",
            "dewidebug watching the bytes this app uses on a metered connection " +
                "[today=${mutableState.value.day} counted=${mutableState.value.bytes}B limitMb=${limitMb()}]",
        )
        return scope.launch {
            while (true) {
                sample()
                delay(sampleEveryMs)
            }
        }
    }

    internal fun sample() {
        val counter = uidBytes()
        if (counter == null) {
            if (!unsupportedSaid) {
                unsupportedSaid = true
                Diag.warn("mobiledata", "this phone does not report the app's own byte counters; nothing is counted")
            }
            return
        }
        val before = mutableState.value
        val previous = lastCounter ?: counter
        val delta = if (counter >= previous) counter - previous else counter
        lastCounter = counter
        val day = today()
        val isMetered = metered()
        val carried = if (day == before.day) before.bytes else 0L
        val bytes = carried + if (isMetered) delta else 0L
        val after = MobileDataState(day, bytes, isMetered)
        mutableState.value = after
        store.save(MobileDataSaved(day, bytes, counter))
        report(before, after, delta)
    }

    private fun report(before: MobileDataState, after: MobileDataState, delta: Long) {
        val limit = limitMb()
        Vitals.set("data.mobileTodayKb", (after.bytes / BYTES_PER_KB).toString())
        if (after.day != before.day) {
            loggedAtBytes = 0L
            Diag.log(
                "mobiledata",
                "dewidebug new day ${after.day}: yesterday's ${before.bytes / BYTES_PER_MB} MB reset to 0"
            )
        }
        if (after.metered != before.metered) {
            Diag.log(
                "mobiledata",
                "dewidebug connection is ${if (after.metered) "metered" else "unmetered"} now " +
                    "[today=${after.bytes / BYTES_PER_MB}MB limitMb=${limit.takeIf { it > 0 } ?: "off"} " +
                    "lastSampleBytes=$delta]",
            )
        }
        if (after.reachedLimit(limit) && !before.reachedLimit(limit)) {
            Diag.warn(
                "mobiledata",
                "dewidebug daily limit reached: ${after.bytes / BYTES_PER_MB} MB of $limit MB used on ${after.day}",
            )
        }
        if (after.bytes - loggedAtBytes >= LOG_EVERY_BYTES) {
            loggedAtBytes = after.bytes
            Diag.log(
                "mobiledata",
                "dewidebug ${after.bytes / BYTES_PER_MB} MB of mobile data used so far on ${after.day} " +
                    "[limitMb=${limit.takeIf { it > 0 } ?: "off"} metered=${after.metered}]",
            )
        }
    }

    private companion object {
        const val SAMPLE_MS = 5_000L
        const val BYTES_PER_KB = 1024L
        const val LOG_EVERY_BYTES = 25 * BYTES_PER_MB
    }
}
