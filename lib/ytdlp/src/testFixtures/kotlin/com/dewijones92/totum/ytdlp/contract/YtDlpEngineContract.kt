package com.dewijones92.totum.ytdlp.contract

import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream

public abstract class YtDlpEngineContract {

    protected abstract fun newEngine(): YtDlpEngine

    private lateinit var player: PlayerServer
    private lateinit var playerUrl: String

    @Before
    public fun serveAPlayer() {
        player = PlayerServer()
        playerUrl = "http://127.0.0.1:${player.port}/s/player/${UUID.randomUUID().toString().take(8)}" +
            "/player_ias.vflset/en_US/base.js"
    }

    @After
    public fun stopServing() {
        player.close()
    }

    @Test
    public fun warmUpLeavesTheEngineReadyWithItsCachesOn(): Unit = runBlocking {
        val engine = newEngine()

        engine.warmUp()

        val state = engine.engineState()
        assertTrue("warmUp must leave the engine warm: ${state.describe()}", state.warm)
        assertTrue("the solver must be built and kept: ${state.describe()}", state.solverReady)
        assertTrue("the preprocessed player cache must be on: ${state.describe()}", state.solverPlayerCache)
        assertTrue("the per-instance player caches must be shared: ${state.describe()}", state.sharedPlayerCaches)
        assertTrue("one HTTP pool must be shared: ${state.describe()}", state.networkShared)
    }

    @Test
    public fun aWarmedPlayerIsOnDiskAndASolveForItNeitherFetchesNorParsesIt(): Unit = runBlocking {
        val engine = newEngine()
        engine.warmUp()

        val warmth = engine.warmPlayer(playerUrl)

        assertTrue("warming a player must work: $warmth", warmth.ok)
        val warmed = engine.engineState()
        assertTrue("the player must be held after warming: ${warmed.describe()}", playerUrl in warmed.preprocessedPlayers)
        assertEquals("the player is fetched once, by the warming", 1, player.fetches.get())

        val solved = engine.solveN(listOf(CHALLENGE), playerUrl)

        assertTrue("the challenge must be solved: $solved", solved.containsKey(CHALLENGE))
        val after = engine.engineState()
        assertEquals("a solve for a warmed player parsed it again", warmed.playerParses, after.playerParses)
        assertEquals("a solve for a warmed player fetched it again", warmed.playerFetches, after.playerFetches)
        assertEquals("a solve for a warmed player went to the network", 1, player.fetches.get())
    }

    @Test
    public fun aSecondSolveForTheSamePlayerDoesNotFetchOrParseItAgain(): Unit = runBlocking {
        val engine = newEngine()
        engine.warmUp()
        val before = engine.engineState()

        val first = engine.solveN(listOf(CHALLENGE), playerUrl)
        val afterFirst = engine.engineState()
        val second = engine.solveN(listOf("$CHALLENGE-two"), playerUrl)
        val afterSecond = engine.engineState()

        assertTrue("the first solve must work: $first", first.containsKey(CHALLENGE))
        assertTrue("the second solve must work: $second", second.isNotEmpty())
        assertEquals("a new build is parsed once", before.playerParses + 1, afterFirst.playerParses)
        assertEquals("the second solve parsed the player again", afterFirst.playerParses, afterSecond.playerParses)
        assertEquals("the second solve fetched the player again", afterFirst.playerFetches, afterSecond.playerFetches)
        assertEquals("the player went to the network more than once", 1, player.fetches.get())
    }

    @Test
    public fun aNewEngineOverTheSameCacheFindsTheWarmedPlayerWithoutFetchingIt(): Unit = runBlocking {
        val first = newEngine()
        first.warmUp()
        assertTrue(first.warmPlayer(playerUrl).ok)

        val second = newEngine()
        second.warmUp()
        val before = second.engineState()
        val solved = second.solveN(listOf(CHALLENGE), playerUrl)

        assertTrue("the solve must work: $solved", solved.containsKey(CHALLENGE))
        assertEquals("a restart re-parsed a player the disk held", before.playerParses, second.engineState().playerParses)
        assertEquals("a restart re-fetched a player the disk held", 1, player.fetches.get())
    }

    private class PlayerServer {
        val fetches = AtomicInteger()
        private val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val body: ByteArray = GZIPInputStream(
            checkNotNull(PlayerServer::class.java.classLoader?.getResourceAsStream(FIXTURE)) { "no $FIXTURE" },
        ).use { input -> ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray() }

        init {
            Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    Thread { answer(client) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }

        private fun answer(client: java.net.Socket) = client.use {
            val reader = it.getInputStream().bufferedReader()
            val request = reader.readLine().orEmpty()
            while (reader.readLine().orEmpty().isNotEmpty()) continue
            if (request.startsWith("GET")) fetches.incrementAndGet()
            val head = "HTTP/1.1 200 OK\r\nContent-Type: application/javascript\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().apply {
                write(head.toByteArray())
                write(body)
                flush()
            }
        }

        fun close() = socket.close()
    }

    private companion object {
        const val FIXTURE = "player-1f293754.js.gz"
        const val CHALLENGE = "Ab1cdEfGhIjKlMn-"
        const val BACKLOG = 16
    }
}
