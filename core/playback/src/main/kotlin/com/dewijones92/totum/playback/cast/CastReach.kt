package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.CastProblem
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class CastReach(
    private val tvs: CastTvPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val later: (Long, () -> Unit) -> Unit = defaultLater(),
) {

    @Volatile private var lastRequestAt = Long.MIN_VALUE

    @Volatile private var warned = false

    fun noAddress() = tvs.problem(CastProblem.NO_ADDRESS)

    fun handedOver(mediaId: String, token: String) {
        val handedAt = now()
        later(WAIT_MS) { check(mediaId, token, handedAt) }
    }

    fun reached(mediaId: String, from: String) {
        val previous = lastRequestAt
        lastRequestAt = now()
        if (previous == Long.MIN_VALUE || warned) {
            Diag.log("cast", "$mediaId: the TV ($from) is fetching from the phone")
        }
        if (warned || previous == Long.MIN_VALUE) tvs.problem(null)
        warned = false
    }

    fun clear() {
        lastRequestAt = Long.MIN_VALUE
        warned = false
    }

    private fun check(mediaId: String, token: String, handedAt: Long) {
        if (lastRequestAt >= handedAt) return
        warned = true
        Diag.warn(
            "cast",
            "$mediaId: the TV has asked the phone for nothing in ${WAIT_MS / MS_PER_S}s since token " +
                "${token.take(TOKEN_SHOWN)} was handed over; it cannot reach this address, or is not trying",
        )
        tvs.problem(CastProblem.TV_NOT_FETCHING)
    }

    private companion object {
        const val WAIT_MS = 12_000L
        const val MS_PER_S = 1_000L
        const val TOKEN_SHOWN = 6

        fun defaultLater(): (Long, () -> Unit) -> Unit {
            val timer = Executors.newSingleThreadScheduledExecutor {
                Thread(
                    it,
                    "cast-reach"
                ).apply { isDaemon = true }
            }
            return { delayMs, run -> timer.schedule(run, delayMs, TimeUnit.MILLISECONDS) }
        }
    }
}
