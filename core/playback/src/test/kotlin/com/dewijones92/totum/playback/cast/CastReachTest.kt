package com.dewijones92.totum.playback.cast

import com.dewijones92.totum.playback.CastProblem
import org.junit.Assert.assertEquals
import org.junit.Test

class CastReachTest {

    private var clock = 0L
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private val problems = mutableListOf<CastProblem?>()
    private val tvs = object : CastTvPort {
        override fun current(): CastTv? = null
        override fun videos(refused: Map<String, String>, sentHeights: Map<String, Int>) = Unit
        override fun problem(now: CastProblem?) {
            problems += now
        }
    }
    private val reach = CastReach(tvs, { clock }, { delay, run -> pending += (clock + delay) to run })

    private fun advanceTo(ms: Long) {
        clock = ms
        pending.filter { it.first <= ms }.forEach {
            pending.remove(it)
            it.second()
        }
    }

    @Test
    fun `a TV that asks for nothing after a hand-over is reported`() {
        reach.handedOver("a", "token-a")
        advanceTo(12_000)
        assertEquals(listOf(CastProblem.TV_NOT_FETCHING), problems)
    }

    @Test
    fun `the next item queued behind a playing one raises nothing while the TV fetches the playing one`() {
        reach.handedOver("a", "token-a")
        advanceTo(1_000)
        reach.reached("a", "tv")
        reach.handedOver("b", "token-b")
        advanceTo(5_000)
        reach.reached("a", "tv")
        advanceTo(9_000)
        reach.reached("a", "tv")
        advanceTo(20_000)
        assertEquals(listOf<CastProblem?>(null), problems)
    }

    @Test
    fun `a warning clears when the TV starts fetching`() {
        reach.handedOver("a", "token-a")
        advanceTo(12_000)
        reach.reached("a", "tv")
        assertEquals(listOf(CastProblem.TV_NOT_FETCHING, null), problems)
    }
}
