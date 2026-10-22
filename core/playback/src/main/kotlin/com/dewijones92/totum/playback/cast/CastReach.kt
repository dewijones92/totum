package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.playback.CastProblem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class CastReach(private val tvs: CastTvPort) {

    private val watchdog = Executors.newSingleThreadScheduledExecutor {
        Thread(
            it,
            "cast-reach"
        ).apply { isDaemon = true }
    }
    private val waiting = ConcurrentHashMap<String, Long>()
    private val latest = ConcurrentHashMap<String, String>()

    fun noAddress() = tvs.problem(CastProblem.NO_ADDRESS)

    fun handedOver(mediaId: String, token: String) {
        waiting[token] = System.currentTimeMillis()
        latest[mediaId] = token
        watchdog.schedule({ check(mediaId, token) }, WAIT_S, TimeUnit.SECONDS)
    }

    fun reached(mediaId: String, token: String, from: String) {
        val handedAt = waiting.remove(token) ?: return
        Diag.log(
            "cast",
            "$mediaId: the TV ($from) reached the phone ${System.currentTimeMillis() - handedAt}ms after the hand-over",
        )
        tvs.problem(null)
    }

    fun clear() {
        waiting.clear()
        latest.clear()
    }

    private fun check(mediaId: String, token: String) {
        if (!waiting.containsKey(token) || latest[mediaId] != token) return
        Diag.warn(
            "cast",
            "$mediaId: the TV has asked the phone for nothing in ${WAIT_S}s (token ${token.take(TOKEN_SHOWN)}); " +
                "it cannot reach this address, or is not trying",
        )
        tvs.problem(CastProblem.TV_NOT_FETCHING)
    }

    private companion object {
        const val WAIT_S = 12L
        const val TOKEN_SHOWN = 6
    }
}
