package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.ytdlp.fake.FakeYtDlpEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerWarmerTest {

    private val engine = FakeYtDlpEngine()
    private var current: String? = "https://www.youtube.com/s/player/aaaa1111/base.js"
    private val warmer = PlayerWarmer(engine, { current })

    @Test
    fun `the engine is warmed once at start`() = runBlocking {
        warmer.warmEngine()

        assertEquals(1, engine.warmUps)
    }

    @Test
    fun `the current player is warmed, and not again while it is the same build`() = runBlocking {
        warmer.warmPlayer()
        warmer.warmPlayer()

        assertEquals(listOf("https://www.youtube.com/s/player/aaaa1111/base.js"), engine.warmedPlayers)
    }

    @Test
    fun `a new build is warmed when it appears`() = runBlocking {
        warmer.warmPlayer()
        current = "https://www.youtube.com/s/player/bbbb2222/base.js"

        warmer.warmPlayer()

        assertEquals(2, engine.warmedPlayers.size)
        assertEquals(current, engine.warmedPlayers.last())
    }

    @Test
    fun `a warming that failed is tried again next time`() = runBlocking {
        engine.warmPlayerOk = false
        warmer.warmPlayer()
        engine.warmPlayerOk = true

        warmer.warmPlayer()

        assertEquals(2, engine.warmedPlayers.size)
    }

    @Test
    fun `no known build means nothing is warmed and nothing throws`() = runBlocking {
        current = null

        warmer.warmPlayer()

        assertEquals(emptyList<String>(), engine.warmedPlayers)
    }
}
