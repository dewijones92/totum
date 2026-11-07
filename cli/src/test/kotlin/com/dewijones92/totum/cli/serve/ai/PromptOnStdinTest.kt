package com.dewijones92.totum.cli.serve.ai

import com.dewijones92.totum.cli.serve.BrainFailure
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PromptOnStdinTest {
    private val dir = Files.createTempDirectory("fake-claude").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun fakeClaude(body: String): String = File(dir, "claude").apply {
        writeText("#!/bin/sh\n$body\n")
        setExecutable(true)
    }.absolutePath

    private fun completion(message: String) = Completion("transcript", "transcript-digest", "system", message, "{}")

    @Test
    fun `a prompt of 180 KB of CJK reaches claude whole, on stdin, and is not an argument`() = runBlocking {
        val bytesRead = fakeClaude(
            "n=$(wc -c)\nprintf '{\"is_error\":false,\"structured_output\":{\"bytes\":%s}}' \"\$n\"",
        )
        val message = "界".repeat(60_000)

        val out = ClaudeCodeCompleter(claude = bytesRead).complete(completion(message))

        assertEquals(message.toByteArray().size, out.getValue("bytes").jsonPrimitive.int)
    }

    @Test
    fun `a prompt too long to send is a typed, emoji-led failure and claude is never run`() = runBlocking {
        val never = fakeClaude("echo ran > ${dir.absolutePath}/ran")
        try {
            ClaudeCodeCompleter(claude = never).complete(completion("x".repeat(2_000_000)))
            fail("a 2 MB prompt was sent")
        } catch (failure: BrainFailure) {
            assertTrue(failure.message!!, failure.message!!.startsWith("❌ The text is too long to send"))
        }
        assertEquals(false, File(dir, "ran").exists())
    }

    @Test
    fun `claude that cannot be started is a typed, emoji-led failure`() = runBlocking {
        try {
            ClaudeCodeCompleter(claude = File(dir, "missing").absolutePath).complete(completion("hello"))
            fail("a missing claude ran")
        } catch (failure: BrainFailure) {
            assertTrue(failure.message!!, failure.message!!.startsWith("❌ Claude could not be run"))
        }
    }
}
