package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.ytdlp.YtDlpEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class PlayerWarmer(
    private val engine: YtDlpEngine,
    private val currentPlayerUrl: suspend () -> String?,
    private val everyMs: Long = CHECK_EVERY_MS,
) {
    private var warmed: String? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            warmEngine()
            while (true) {
                warmPlayer()
                delay(everyMs)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun warmEngine() {
        val began = System.nanoTime()
        try {
            engine.warmUp()
            Diag.log("warm", "dewidebug the engine is warm after ${(System.nanoTime() - began) / NANOS_PER_MS}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("warm", "dewidebug the engine could not be warmed: ${e.message}", e)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun warmPlayer() {
        val url = try {
            currentPlayerUrl()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Diag.warn("warm", "dewidebug asking for the current player failed: ${e.message}", e)
            null
        }
        if (url == null) {
            Diag.log(
                "warm",
                "dewidebug no player build is known yet, so none was warmed (last warmed: ${warmed ?: "none"})"
            )
            return
        }
        if (url == warmed) {
            Diag.log("warm", "dewidebug the player is the one already warmed, nothing to do ($url)")
            return
        }
        val warmth = engine.warmPlayer(url)
        Diag.log(
            "warm",
            "dewidebug player $url: ok=${warmth.ok} alreadyHeld=${warmth.alreadyHeld} took=${warmth.tookMs}ms " +
                "detail=${warmth.detail ?: "none"} (was ${warmed ?: "none"})",
        )
        if (warmth.ok) warmed = url
    }

    private companion object {
        const val CHECK_EVERY_MS = 60 * 60 * 1000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
