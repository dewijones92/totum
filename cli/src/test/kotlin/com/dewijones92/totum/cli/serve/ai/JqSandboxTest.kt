package com.dewijones92.totum.cli.serve.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class JqSandboxTest {

    private val script = File("../server/totum-cli/jq-sandbox.sh").canonicalFile
    private val folder = Files.createTempDirectory("jq-sandbox").toFile().apply {
        resolve("state.json").writeText("""{"queue":[{"t":"a"},{"t":"b"}],"n":3}""")
        resolve("other.json").writeText("""{"x":1}""")
        resolve("sub").mkdirs()
        resolve("sub/inner.json").writeText("""{"inner":true}""")
    }

    private class Ran(val exit: Int, val out: String, val err: String)

    private fun jq(vararg args: String, stdin: String? = null, env: Map<String, String> = emptyMap()): Ran {
        assertTrue("the sandbox script is missing at $script", script.isFile)
        val builder = ProcessBuilder(listOf("sh", script.path) + args).directory(folder)
        builder.environment().putAll(env)
        val process = builder.start()
        stdin?.let { process.outputStream.use { out -> out.write(it.toByteArray()) } } ?: process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        val err = process.errorStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        return Ran(process.exitValue(), out.trim(), err.trim())
    }

    private fun assertRefused(vararg args: String) {
        val ran = jq(*args)
        assertEquals("exit for ${args.toList()}: ${ran.err}", 2, ran.exit)
        assertEquals("nothing read for ${args.toList()}", "", ran.out)
        assertTrue(ran.err, ran.err.startsWith("jq-sandbox: refused"))
    }

    @Test
    fun `a query over a file in its own folder runs`() {
        val ran = jq("-r", ".queue | map(.t) | join(\",\")", "state.json")

        assertEquals(0, ran.exit)
        assertEquals("a,b", ran.out)
    }

    @Test
    fun `a dot-slash path, a file in a subfolder, stdin and no file at all all run`() {
        assertEquals("3", jq(".n", "./state.json").out)
        assertEquals("true", jq(".inner", "sub/inner.json").out)
        assertEquals("1", jq(".a", stdin = """{"a":1}""").out)
        assertEquals("2", jq("-n", "1+1").out)
    }

    @Test
    fun `options that carry data in rather than a path are fine`() {
        assertEquals("\"hi\"", jq("-n", "--arg", "v", "hi", "\$v").out)
        assertEquals("1", jq("-n", "--slurpfile", "v", "other.json", "\$v[0].x").out)
        assertEquals("3", jq("-c", "--argjson", "k", "3", ".n", "state.json").out)
    }

    @Test
    fun `jq sees an empty environment so a secret in the service's cannot be read`() {
        val ran = jq(
            "-r",
            "-n",
            "[env | length, (env.CLAUDE_CODE_OAUTH_TOKEN // \"none\"), (\$ENV.SECRET_THING // \"none\")] | @csv",
            env = mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "sk-live-123", "SECRET_THING" to "hunter2"),
        )

        assertEquals(0, ran.exit)
        assertEquals("0,\"none\",\"none\"", ran.out)
        assertFalse(ran.out.contains("sk-live-123"))
    }

    @Test
    fun `an absolute path is refused wherever it is used`() {
        assertRefused(".", "/etc/passwd")
        assertRefused("-R", ".", "/proc/self/environ")
        assertRefused("--rawfile", "v", "/etc/hostname", "-n", "\$v")
        assertRefused("-f", "/tmp/filter.jq")
    }

    @Test
    fun `a home-relative path is refused`() {
        assertRefused(".", "~/.claude/.credentials.json")
        assertRefused("--slurpfile", "v", "~/.ssh/id_ed25519", "-n")
    }

    @Test
    fun `climbing out of the folder is refused in every spelling`() {
        assertRefused(".", "../secret.json")
        assertRefused(".", "sub/../../secret.json")
        assertRefused(".", "sub/..")
        assertRefused(".", "..")
        assertRefused("--rawfile", "v", "./../x", "-n")
    }

    @Test
    fun `a module search path is refused so imports cannot reach outside`() {
        assertRefused("-L/tmp", "-n", "1")
        assertRefused("-L", "/tmp", "-n", "1")
        assertRefused("--library-path", "/tmp", "-n", "1")
        assertRefused("--library-path=/tmp", "-n", "1")
    }

    @Test
    fun `an import or include in the program is refused`() {
        assertRefused("-n", "import \"lib\" as l; 1")
        assertRefused("-n", "include \"lib\"; 1")
        assertRefused("-n", "import\"lib\" as l; 1")
        assertRefused("-n", "1 | include\t\"lib\"; 2")
    }

    @Test
    fun `a refusal says which argument and why, and names no secret`() {
        val ran = jq(".", "/etc/shadow")

        assertTrue(ran.err, ran.err.contains("\"/etc/shadow\""))
        assertTrue(ran.err, ran.err.contains("only the files in its own folder"))
    }

    @Test
    fun `a word that merely contains import is not an import`() {
        val ran = jq("-r", ".important", stdin = """{"important":"yes"}""")

        assertEquals("yes", ran.out)
    }
}
