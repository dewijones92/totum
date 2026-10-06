package com.dewijones92.totum.ytdlp.chaquopy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class V8ChallengeRuntimeTest {

    private val isolates = NodeIsolates()
    private val runtime = V8ChallengeRuntime(isolates, playersKept = 2)

    @Before
    fun needsNode() {
        assumeTrue("node is not on the PATH", NodeIsolate.node != null || System.getenv("CI") != null)
    }

    @After
    fun stop() {
        runtime.close()
        isolates.close()
    }

    @Test
    fun `a new build is solved in full and its preprocessed player kept for the next solve`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)

        val first = runtime.solveWithPlayer(url("aaa"), "\"raw-aaa\"", preprocessed = false, requestsJson = N1)
        val second = runtime.solveKept(url("aaa"), N2)

        assertEquals("raw-aaa:x1", answer(first, "x1"))
        assertEquals(
            "PRE(raw-aaa)",
            Json.parseToJsonElement(first).jsonObject["preprocessed_player"]?.jsonPrimitive?.content
        )
        assertEquals("pre:PRE(raw-aaa):x2", answer(checkNotNull(second), "x2"))
    }

    @Test
    fun `a player that was never kept is not solved from memory`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)

        assertNull(runtime.solveKept(url("aaa"), N1))
    }

    @Test
    fun `a cached preprocessed player is kept and reused`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)

        runtime.solveWithPlayer(url("aaa"), "\"PRE(cached)\"", preprocessed = true, requestsJson = N1)

        assertEquals("pre:PRE(cached):x2", answer(checkNotNull(runtime.solveKept(url("aaa"), N2)), "x2"))
    }

    @Test
    fun `a third build drops the oldest from the isolate as well as from the list`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)
        listOf("aaa", "bbb", "ccc").forEach {
            runtime.solveWithPlayer(url(it), "\"raw-$it\"", preprocessed = false, requestsJson = N1)
        }

        val held = Json.parseToJsonElement(checkNotNull(runtime.solveKept(url("ccc"), N1))).jsonObject
            .getValue("held").jsonArray.map { it.jsonPrimitive.content }

        assertNull(runtime.solveKept(url("aaa"), N1))
        assertTrue(runtime.solveKept(url("bbb"), N1) != null)
        assertEquals(listOf(url("bbb"), url("ccc")), held)
    }

    @Test
    fun `a solver error keeps nothing`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)

        val out = runtime.solveWithPlayer(url("aaa"), "\"BROKEN\"", preprocessed = false, requestsJson = N1)

        assertEquals("error", Json.parseToJsonElement(out).jsonObject["type"]?.jsonPrimitive?.content)
        assertNull(runtime.solveKept(url("aaa"), N1))
    }

    @Test
    fun `preloaded players are solvable at once, and only after the library is in`() {
        assertFalse(runtime.keepPlayer(url("aaa"), "\"PRE(warm)\""))
        runtime.loadLibrary("lib1", FAKE_SOLVER)

        assertTrue(runtime.keepPlayer(url("aaa"), "\"PRE(warm)\""))
        assertTrue(runtime.keepPlayer(url("bbb"), "\"PRE(warm2)\""))

        assertEquals("pre:PRE(warm):x1", answer(checkNotNull(runtime.solveKept(url("aaa"), N1)), "x1"))
    }

    @Test
    fun `the library is loaded once per isolate`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)

        assertTrue(runtime.hasLibrary("lib1"))
        assertFalse(runtime.hasLibrary("lib2"))
        assertEquals(1, isolates.opened.size)
    }

    @Test
    fun `a changed library starts a fresh isolate`() {
        runtime.loadLibrary("lib1", FAKE_SOLVER)
        runtime.solveWithPlayer(url("aaa"), "\"raw-aaa\"", preprocessed = false, requestsJson = N1)

        runtime.loadLibrary("lib2", FAKE_SOLVER)

        assertTrue(isolates.opened.first().closed)
        assertEquals(2, isolates.opened.size)
        assertNull(runtime.solveKept(url("aaa"), N1))
    }

    private fun answer(output: String, challenge: String) =
        Json.parseToJsonElement(output).jsonObject.getValue("responses").jsonArray[0].jsonObject
            .getValue("data").jsonObject.getValue(challenge).jsonPrimitive.content

    private fun url(build: String) = "https://www.youtube.com/s/player/$build/player_ias.vflset/en_US/base.js"

    private companion object {
        const val N1 = """[{"type": "n", "challenges": ["x1"]}]"""
        const val N2 = """[{"type": "n", "challenges": ["x2"]}]"""

        const val FAKE_SOLVER = """
var lib = { helper: 1 };
Object.assign(globalThis, lib);
var jsc = function (input) {
  const held = Object.keys(globalThis.__totumPlayers || {});
  const solve = (base) => input.requests.map((r) => ({ type: 'result',
    data: Object.fromEntries(r.challenges.map((c) => [c, base + ':' + c])) }));
  if (input.type === 'player') {
    if (input.player === 'BROKEN') return { type: 'error', error: 'unparseable player' };
    return { type: 'result', held, responses: solve(input.player), preprocessed_player: 'PRE(' + input.player + ')' };
  }
  if (typeof input.preprocessed_player !== 'string') return { type: 'error', error: 'no player' };
  return { type: 'result', held, responses: solve('pre:' + input.preprocessed_player) };
};
"""
    }
}
