package com.dewijones92.totum.cli.serve.power

import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections

class WebOsTvTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tv = FakeSsapServer()
    private val logged = Collections.synchronizedList(mutableListOf<String>())
    private val warned = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var keyFile: java.io.File

    @Before
    fun setUp() {
        keyFile = tmp.newFolder("webos").resolve("webos-lg-tv.json")
        Diag.sink = Diag.Sink { level, _, message, error ->
            logged += message + (error?.message ?: "")
            if (level == Diag.Level.WARN) warned += message
        }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
        tv.close()
    }

    private fun paired(): WebOsTv {
        WebOsKeys(keyFile).write(WebOsKey(tv.clientKey, null))
        return WebOsTv("LG TV", OkWebOsDial(listOf(tv.url)), WebOsKeys(keyFile))
    }

    @Test
    fun `the panel is on only when the TV says Active, since port 3001 stays open in standby`() = runBlocking {
        val set = paired()

        tv.powerState = "Active"
        assertEquals(true, set.panelOn())
        tv.powerState = "Active Standby"
        assertEquals(false, set.panelOn())
        tv.powerState = "Suspend"
        assertEquals(false, set.panelOn())
    }

    @Test
    fun `a TV that has not been paired is never dialled and says to run the pairing`() = runBlocking {
        val set = WebOsTv("LG TV", OkWebOsDial(listOf(tv.url)), WebOsKeys(keyFile))

        assertNull(set.panelOn())
        assertEquals("it has not been paired: run totum webos-pair", set.powerOff())
        assertEquals(0, tv.connectionCount)
    }

    @Test
    fun `it registers with the stored key and asks the TV to turn off`() = runBlocking {
        val set = paired()

        val failure = set.powerOff()

        assertNull(failure)
        assertEquals(
            tv.clientKey,
            tv.registrations.single()["payload"]!!.jsonObject["client-key"]!!.jsonPrimitive.content
        )
        assertEquals("PROMPT", tv.registrations.single()["payload"]!!.jsonObject["pairingType"]!!.jsonPrimitive.content)
        assertTrue(tv.uris().contains("ssap://system/turnOff"))
    }

    @Test
    fun `a TV that closes the connection as it goes to standby has been switched off, not failed`() = runBlocking {
        tv.turnOffAnswer = FakeSsapServer.TurnOff.CLOSE_WITHOUT_ANSWER
        val set = paired()
        val began = System.nanoTime()

        assertNull(set.powerOff())

        val tookMs = (System.nanoTime() - began) / NANOS_PER_MS
        assertTrue("took ${tookMs}ms: the close was noticed, not waited out", tookMs < CLOSE_NOTICED_WITHIN_MS)
    }

    @Test
    fun `a TV that closes the line with 1008 just after we switched it off is not reported as unavailable`() =
        runBlocking {
            val set = paired()
            assertNull(set.powerOff())
            tv.closeNewConnectionsWith = POLICY_VIOLATION

            val panel = set.panelOn()

            assertNull(panel)
            assertTrue(
                logged.joinToString("\n"),
                logged.any { "the TV closed the connection as it switched off" in it }
            )
            assertEquals(warned.joinToString("\n"), emptyList<String>(), warned.filter { "unavailable" in it })
        }

    @Test
    fun `a close that follows no switch off of ours is still reported as the TV being unreachable`() = runBlocking {
        val set = paired()
        tv.closeNewConnectionsWith = POLICY_VIOLATION

        assertNull(set.panelOn())

        val unreachable = "webOS control unavailable, it could not be reached: the TV closed the connection (1008)"
        assertTrue(warned.joinToString("\n"), warned.any { unreachable in it })
    }

    @Test
    fun `a close long after our switch off is not taken as part of it`() = runBlocking {
        var clock = 0L
        WebOsKeys(keyFile).write(WebOsKey(tv.clientKey, null))
        val set = WebOsTv("LG TV", OkWebOsDial(listOf(tv.url)), WebOsKeys(keyFile), now = { clock })
        assertNull(set.powerOff())
        clock += 10 * 60_000L
        tv.closeNewConnectionsWith = POLICY_VIOLATION

        assertNull(set.panelOn())

        assertTrue(warned.joinToString("\n"), warned.any { "the TV closed the connection (1008)" in it })
    }

    @Test
    fun `a TV that refuses to turn off says why`() = runBlocking {
        tv.turnOffAnswer = FakeSsapServer.TurnOff.REFUSE
        tv.turnOffErrorText = "no permission"

        assertEquals("it answered with an error: no permission", paired().powerOff())
    }

    @Test
    fun `a TV that asks to pair again has a bad key, and is not hammered with retries`() = runBlocking {
        tv.knowsStoredKey = false
        val set = paired()

        val first = set.powerOff()
        val second = set.powerOff()

        assertTrue(first!!, first.contains("asked to pair again"))
        assertEquals("it refused the stored key earlier; run totum webos-pair again", second)
        assertEquals("only the first call dialled the TV", 1, tv.connectionCount)
    }

    @Test
    fun `a TV that cannot be reached says so`() = runBlocking {
        val set = paired()
        tv.close()

        val failure = set.powerOff()

        assertTrue(failure!!, failure.startsWith("it could not be reached"))
    }

    @Test
    fun `a TV paired with a certificate is dialled with that certificate pinned`() = runBlocking {
        WebOsKeys(keyFile).write(WebOsKey(tv.clientKey, "ab12cd34ef56"))
        val pins = mutableListOf<String?>()
        val set = WebOsTv(
            "LG TV",
            WebOsDial { pinned ->
                pins += pinned
                OkWebOsDial(listOf(tv.url)).open(null)
            },
            WebOsKeys(keyFile),
        )

        assertNull(set.powerOff())

        assertEquals(listOf<String?>("ab12cd34ef56"), pins)
    }

    @Test
    fun `switching input sends the input id`() = runBlocking {
        val set = paired()

        assertNull(set.switchInput("HDMI_1"))

        val sent = tv.requests.single { it["uri"]!!.jsonPrimitive.content == "ssap://tv/switchInput" }
        assertEquals("HDMI_1", sent["payload"]!!.jsonObject["inputId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the inputs are listed with what is connected`() = runBlocking {
        val inputs = paired().inputs()

        assertEquals(listOf("HDMI_1", "HDMI_2"), inputs.map { it.id })
        assertEquals(listOf(true, false), inputs.map { it.connected })
        assertEquals("Fire Stick", inputs.first().label)
    }

    @Test
    fun `a TV that refuses the signed manifest is registered again unsigned`() = runBlocking {
        tv.refuseSigned = true

        assertNull(paired().powerOff())

        assertEquals(2, tv.registrations.size)
        val signed = tv.registrations.map { it["payload"]!!.jsonObject["manifest"]!!.jsonObject.containsKey("signed") }
        assertEquals(listOf(true, false), signed)
    }

    @Test
    fun `the client key never reaches a log line, worked, refused or gone`() = runBlocking {
        val set = paired()
        set.panelOn()
        set.powerOff()
        tv.turnOffAnswer = FakeSsapServer.TurnOff.REFUSE
        set.powerOff()
        tv.close()
        set.powerOff()

        assertTrue("something was logged", logged.isNotEmpty())
        assertFalse(logged.joinToString("\n"), logged.any { tv.clientKey in it })
    }

    @Test
    fun `the key file is written readable by its owner alone and the key prints redacted`() {
        val keys = WebOsKeys(keyFile)

        keys.write(WebOsKey(tv.clientKey, "ab12cd34ef56"))

        val permissions = Files.getPosixFilePermissions(keyFile.toPath())
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), permissions)
        assertEquals(tv.clientKey, keys.read()!!.clientKey)
        assertEquals("ab12cd34ef56", keys.read()!!.cert)
        assertFalse(keys.read().toString().contains(tv.clientKey))
        assertNotNull(keys.read())
    }

    @Test
    fun `replacing a key keeps the mode`() {
        val keys = WebOsKeys(keyFile)
        keys.write(WebOsKey("first-key-value", null))

        keys.write(WebOsKey("second-key-value", null))

        assertEquals("second-key-value", keys.read()!!.clientKey)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(keyFile.toPath()),
        )
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val CLOSE_NOTICED_WITHIN_MS = 4_000L
        const val POLICY_VIOLATION = 1008
    }
}
