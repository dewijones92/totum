package com.dewijones92.totum.ytdlp.chaquopy

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.Vitals
import com.dewijones92.totum.ytdlp.chaquopy.V8Scripts.shortName

internal interface JsIsolate : AutoCloseable {
    fun evaluate(code: String): String
}

internal interface JsIsolates : AutoCloseable {
    fun usable(): Boolean
    fun open(): JsIsolate
}

internal class V8ChallengeRuntime(
    private val isolates: JsIsolates,
    private val playersKept: Int = PLAYERS_KEPT,
    private val nanoTime: () -> Long = System::nanoTime,
) : JsChallengeRuntime, AutoCloseable {

    private var isolate: JsIsolate? = null
    private var library: String? = null
    private val players = KeptPlayers(playersKept)

    override fun available(): Boolean = isolates.usable()

    @Synchronized
    override fun hasLibrary(key: String): Boolean = isolate != null && library == key

    @Synchronized
    override fun loadLibrary(key: String, code: String) {
        if (library != null && library != key) reset("the solver library changed from $library to $key")
        timed("load library $key") { evaluate("$code\n'loaded';") }
        library = key
    }

    @Synchronized
    override fun keepPlayer(playerKey: String, playerJson: String): Boolean {
        if (library == null) return false
        val drop = players.makeRoomFor(playerKey)
        timed("keep player ${shortName(playerKey)}") { evaluate(V8Scripts.keep(playerKey, playerJson, drop)) }
        players.remember(playerKey)
        return true
    }

    @Synchronized
    override fun solveKept(playerKey: String, requestsJson: String): String? {
        if (playerKey !in players) return null
        Vitals.add("v8.solves.kept")
        return timed("kept solve ${shortName(playerKey)}") {
            evaluate(V8Scripts.solveKept(playerKey, requestsJson))
        }
    }

    @Synchronized
    override fun solveWithPlayer(
        playerKey: String,
        playerJson: String,
        preprocessed: Boolean,
        requestsJson: String,
    ): String {
        check(library != null) { "the solver library is not loaded" }
        val drop = players.makeRoomFor(playerKey)
        val mode = if (preprocessed) "preprocessed" else "raw"
        Vitals.add("v8.solves.$mode")
        val result = timed("$mode solve ${shortName(playerKey)}") {
            evaluate(V8Scripts.solveWithPlayer(playerKey, playerJson, preprocessed, requestsJson, drop))
        }
        if (result.startsWith(V8Scripts.KEPT)) players.remember(playerKey)
        return result.substring(1)
    }

    @Synchronized
    override fun close() {
        reset("closed")
    }

    val state: String
        @Synchronized get() = "isolate=${if (isolate != null) "open" else "none"} library=${library ?: "none"} " +
            "players=$players"

    @Suppress("TooGenericExceptionCaught")
    private fun evaluate(code: String): String {
        val open = isolate ?: isolates.open().also {
            isolate = it
            Diag.log("engine", "v8 isolate opened")
        }
        return try {
            open.evaluate(code)
        } catch (e: Exception) {
            val reason = "${e.javaClass.simpleName}: ${e.message}"
            Vitals.add("v8.failures")
            Vitals.set("v8.lastFailure", reason.take(FAILURE_CHARS))
            reset(reason)
            throw e
        }
    }

    private fun reset(reason: String) {
        if (isolate == null && library == null && players.isEmpty()) return
        Diag.log("engine", "v8 isolate reset ($state): $reason")
        runCatching { isolate?.close() }.onFailure { Diag.warn("engine", "v8 isolate close failed: $it") }
        isolate = null
        library = null
        players.clear()
    }

    private inline fun <T> timed(what: String, block: () -> T): T = timedV8(what, nanoTime, { state }, block)

    private companion object {
        const val PLAYERS_KEPT = 2
        const val FAILURE_CHARS = 200
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

private inline fun <T> timedV8(what: String, nanoTime: () -> Long, state: () -> String, block: () -> T): T {
    val started = nanoTime()
    val result = block()
    Diag.log("engine", "v8 $what in ${(nanoTime() - started) / NANOS_PER_MILLI}ms")
    Vitals.set("v8.state", state())
    return result
}

internal class KeptPlayers(private val limit: Int) {
    private val keys = ArrayDeque<String>()

    operator fun contains(key: String): Boolean = key in keys

    fun isEmpty(): Boolean = keys.isEmpty()

    fun clear() = keys.clear()

    fun makeRoomFor(key: String): List<String> {
        if (key in keys) return emptyList()
        val drop = keys.take((keys.size - limit + 1).coerceAtLeast(0))
        drop.forEach { Diag.log("engine", "v8 dropping kept player ${shortName(it)} for ${shortName(key)}") }
        keys.removeAll(drop.toSet())
        return drop
    }

    fun remember(key: String) {
        keys.remove(key)
        keys.addLast(key)
    }

    override fun toString(): String = keys.joinToString(",", "[", "]") { shortName(it) }
}
