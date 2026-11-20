package com.dewijones92.totum.cli.serve.cast

import com.dewijones92.totum.cli.Command
import com.dewijones92.totum.cli.serve.power.FakeSsapServer
import com.dewijones92.totum.cli.serve.power.OkWebOsDial
import com.dewijones92.totum.cli.serve.power.WebOsDial
import com.dewijones92.totum.cli.serve.power.WebOsKeys
import com.dewijones92.totum.cli.serve.power.WebOsLine
import com.dewijones92.totum.common.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections

class WebOsPairingTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tv = FakeSsapServer()
    private val said = Collections.synchronizedList(mutableListOf<String>())
    private val logged = Collections.synchronizedList(mutableListOf<String>())
    private lateinit var data: File

    private val times = WebOsPairingTimes(waitForPinMs = 10_000, waitForResultMs = 10_000, pollMs = 20)

    @Before
    fun setUp() {
        data = tmp.newFolder("data")
        Diag.sink = Diag.Sink { _, _, message, error -> logged += message + (error?.message ?: "") }
    }

    @After
    fun tearDown() {
        Diag.sink = Diag.Sink { _, _, _, _ -> }
        tv.close()
    }

    private val env: (String) -> String? = {
        mapOf("TOTUM_WEBOS_TARGETS" to "LG TV=192.168.0.219", "TOTUM_DATA" to data.path)[it]
    }

    private val keyFile: File get() = File(data, "webos/webos-lg-tv.json")

    private suspend fun pair(name: String = "LG TV", pin: String? = null, typed: () -> String? = { null }): Int =
        webOsPair(
            Command.WebOsPair(name, pin),
            said::add,
            env,
            dialFor = { OkWebOsDial(listOf(tv.url)) },
            typed = typed,
            times = times,
        )

    private fun everything(): String = (said + logged).joinToString("\n")

    @Test
    fun `a PIN typed at the terminal pairs the TV, stores the key with mode 600 and lists the inputs`() = runBlocking {
        val code = pair(typed = { tv.pin })

        assertEquals(said.joinToString("\n"), 0, code)
        assertEquals(tv.clientKey, WebOsKeys(keyFile).read()!!.clientKey)
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(keyFile.toPath()),
        )
        assertTrue(said.any { it.contains("HDMI_1") && it.contains("Fire Stick") })
        assertTrue(said.any { it.startsWith("✅ Paired LG TV") })
    }

    @Test
    fun `the pairing asks for a PIN, not for a prompt on the TV`() = runBlocking {
        pair(typed = { tv.pin })

        val first = tv.registrations.first()["payload"]!!.jsonObject
        assertEquals("PIN", first["pairingType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the certificate the TV presented is stored with the key, so later dials can pin it`() = runBlocking {
        val real = OkWebOsDial(listOf(tv.url))

        val code = webOsPair(
            Command.WebOsPair("LG TV", null),
            said::add,
            env,
            dialFor = { WebOsDial { pinned -> presenting(real.open(pinned), "ab12cd34ef56") } },
            typed = { tv.pin },
            times = times,
        )

        assertEquals(0, code)
        assertEquals("ab12cd34ef56", WebOsKeys(keyFile).read()!!.cert)
    }

    private fun presenting(line: WebOsLine, fingerprint: String): WebOsLine = object : WebOsLine by line {
        override val certSeen: String? = fingerprint
    }

    @Test
    fun `the key is never printed or logged`() = runBlocking {
        pair(typed = { tv.pin })

        assertFalse(everything(), tv.clientKey in everything())
    }

    @Test
    fun `a wrong PIN is refused and nothing is stored`() = runBlocking {
        val code = pair(typed = { "999999" })

        assertEquals(1, code)
        assertFalse(keyFile.exists())
        assertTrue(said.joinToString("\n"), said.any { it.contains("did not accept that PIN") })
    }

    @Test
    fun `a TV that is asking on its own screen is waited for`() = runBlocking {
        tv.pairingType = "PROMPT"

        val code = pair()

        assertEquals(0, code)
        assertTrue(said.any { it.contains("press Allow") })
        assertEquals(tv.clientKey, WebOsKeys(keyFile).read()!!.clientKey)
    }

    @Test
    fun `a PIN given with --pin reaches the pairing that is waiting for it`() = runBlocking {
        val waiting = async(Dispatchers.IO) { pair() }
        withTimeout(10_000) { while (said.none { it.contains("showing a PIN") }) delay(20) }

        val second = pair(pin = tv.pin)
        val first = withTimeout(10_000) { waiting.await() }

        assertEquals(0, second)
        assertEquals(0, first)
        assertEquals(tv.clientKey, WebOsKeys(keyFile).read()!!.clientKey)
        assertFalse("the hand-over file is gone", File(data, "webos/pending-lg-tv.pin").exists())
        assertFalse("the waiting marker is gone", File(data, "webos/pending-lg-tv.wait").exists())
    }

    @Test
    fun `a PIN with no pairing waiting says how to start one`() = runBlocking {
        val code = pair(pin = "123456")

        assertEquals(1, code)
        assertTrue(said.joinToString("\n"), said.any { it.contains("No pairing of LG TV is waiting") })
        assertEquals(0, tv.connectionCount)
    }

    @Test
    fun `a PIN that is not 4 to 12 digits is refused before anything is dialled`() = runBlocking {
        val code = pair(pin = "12")

        assertEquals(1, code)
        assertTrue(said.any { it.contains("4 to 12 digits") })
        assertEquals(0, tv.connectionCount)
    }

    @Test
    fun `a TV that is not in the configuration is refused and the list is shown`() = runBlocking {
        val code = pair(name = "Bedroom")

        assertEquals(1, code)
        assertTrue(said.single().contains("not in TOTUM_WEBOS_TARGETS (LG TV)"))
        assertEquals(0, tv.connectionCount)
    }

    @Test
    fun `no PIN in time is a refusal that leaves no files behind`() = runBlocking {
        val impatient = WebOsPairingTimes(waitForPinMs = 100, waitForResultMs = 100, pollMs = 20)

        val code = webOsPair(
            Command.WebOsPair("LG TV", null),
            said::add,
            env,
            dialFor = { OkWebOsDial(listOf(tv.url)) },
            typed = { null },
            times = impatient,
        )

        assertEquals(1, code)
        assertFalse(keyFile.exists())
        assertFalse(File(data, "webos/pending-lg-tv.wait").exists())
    }
}
