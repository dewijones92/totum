package com.dewijones92.totum.ytdlp.chaquopy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class V8ChallengeRuntimeFailureTest {

    private class ScriptedIsolate(private val replies: ArrayDeque<() -> String>) : JsIsolate {
        var closed = false
        override fun evaluate(code: String): String = replies.removeFirst()()
        override fun close() {
            closed = true
        }
    }

    private class ScriptedIsolates(vararg isolates: ScriptedIsolate) : JsIsolates {
        val waiting = ArrayDeque(isolates.toList())
        var opens = 0
        override fun usable() = true
        override fun open(): JsIsolate {
            opens++
            return waiting.removeFirst()
        }
        override fun close() = Unit
    }

    @Test
    fun `a failed evaluation forgets the isolate, the library and every kept player`() {
        val dying = ScriptedIsolate(
            ArrayDeque(listOf({ "loaded" }, { "K{}" }, { throw IllegalStateException("SandboxDeadException: gone") })),
        )
        val fresh = ScriptedIsolate(ArrayDeque(listOf({ "loaded" })))
        val isolates = ScriptedIsolates(dying, fresh)
        val runtime = V8ChallengeRuntime(isolates)
        runtime.loadLibrary("lib1", "code")
        runtime.solveWithPlayer("p1", "\"x\"", preprocessed = false, requestsJson = "[]")

        try {
            runtime.solveKept("p1", "[]")
            fail("the failure must reach the caller so yt-dlp falls back to QuickJS")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("gone"))
        }

        assertTrue(dying.closed)
        assertFalse(runtime.hasLibrary("lib1"))
        assertNull(runtime.solveKept("p1", "[]"))
        runtime.loadLibrary("lib1", "code")
        assertEquals(2, isolates.opens)
    }

    @Test
    fun `a solve without the library refuses rather than evaluating`() {
        val runtime = V8ChallengeRuntime(ScriptedIsolates())

        try {
            runtime.solveWithPlayer("p1", "\"x\"", preprocessed = false, requestsJson = "[]")
            fail("expected a refusal")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("library"))
        }
    }
}
