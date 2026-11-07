package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRedactorTest {

    private val token = "d3v1ce-t0ken-9f8e7d6c"
    private val key = "prowl4rr-k3y-123456"
    private val redactor = ReportRedactor(
        secrets = listOf(token, key),
        hosts = listOf("home.example.org", "totum.home.example.org", "https://crashlog.home.example.org/ingest"),
    )

    @Test
    fun `a known secret is removed wherever it appears`() {
        val out = redactor.redact("sent header X-Foo=$token and again $token, key $key")

        assertFalse(out, token in out)
        assertFalse(out, key in out)
    }

    @Test
    fun `the home server address is removed in every form it is written`() {
        val out = redactor.redact(
            "GET https://totum.home.example.org/fetch/files?id=1, host HOME.EXAMPLE.ORG, " +
                "and https://crashlog.home.example.org/ingest",
        )

        assertFalse(out, "example.org" in out.lowercase())
        assertTrue(out, ReportRedactor.HOME_SERVER in out)
    }

    @Test
    fun `token-looking query parameters and fragments are removed even when unknown`() {
        val out = redactor.redact(
            "http://192.168.1.20:8080/screen#key=abc123XYZ and https://x.test/p?token=zzz999&b=1&api_key=qq11",
        )

        assertFalse(out, "abc123XYZ" in out)
        assertFalse(out, "zzz999" in out)
        assertFalse(out, "qq11" in out)
        assertTrue(out, "b=1" in out)
    }

    @Test
    fun `authorization headers and bearer tokens are removed to the end of the line`() {
        val out = redactor.redact("Authorization: Bearer abcdefgh12345678\nX-Totum-Token: someothervalue\nok")

        assertFalse(out, "abcdefgh12345678" in out)
        assertFalse(out, "someothervalue" in out)
        assertTrue(out, out.endsWith("ok"))
    }

    @Test
    fun `google tokens and jwts are removed`() {
        val out = redactor.redact(
            "ya29.a0AfH6SMBxyz-_123 and 1//0gAbCdEfGhIjKlMnOpQrStUvWx and eyJhbGciOiJI.eyJzdWIiOiIx.sig",
        )

        assertFalse(out, "ya29" in out)
        assertFalse(out, "1//0g" in out)
        assertFalse(out, "eyJ" in out)
    }

    @Test
    fun `email addresses are removed`() {
        val out = redactor.redact("signed in as someone.name+tag@mail.example.com today")

        assertFalse(out, "someone" in out)
        assertTrue(out, ReportRedactor.EMAIL in out)
    }

    @Test
    fun `ipv4 and ipv6 addresses are removed but versions and timestamps stay`() {
        val out = redactor.redact(
            "connect 10.0.2.2:5555 and 192.168.0.44. v0.1.823 at 21:03:48.123 build 1.2.3.4.5 " +
                "[2001:db8:0:0:0:0:0:1] fe80::1ff:fe23:4567:890a%wlan0",
        )

        assertFalse(out, "10.0.2.2" in out)
        assertFalse(out, "192.168.0.44" in out)
        assertFalse(out, "2001:db8" in out)
        assertFalse(out, "fe80" in out)
        assertTrue(out, "v0.1.823 at 21:03:48.123 build 1.2.3.4.5" in out)
    }

    @Test
    fun `a number that is not an address is left alone`() {
        assertEquals("version 300.400.500.600 stays", redactor.redact("version 300.400.500.600 stays"))
    }

    @Test
    fun `magnet links and long opaque strings are removed`() {
        val out = redactor.redact(
            "magnet:?xt=urn:btih:ABCDEF0123456789&dn=Film and id 0123456789abcdef0123456789abcdef01234567",
        )

        assertFalse(out, "btih" in out)
        assertFalse(out, "0123456789abcdef0123456789abcdef" in out)
    }

    @Test
    fun `credentials inside a url are removed`() {
        val out = redactor.redact("fetched https://user:hunter2@files.example.org/a.mp3")

        assertFalse(out, "hunter2" in out)
        assertTrue(out, "files.example.org/a.mp3" in out)
    }

    @Test
    fun `ordinary text and stack frames survive untouched`() {
        val text = "java.lang.IllegalStateException: queue was empty at com.dewijones92.totum.queue.PlaybackQueue.next"

        assertEquals(text, redactor.redact(text))
    }

    @Test
    fun `redacting twice gives the same answer`() {
        val once = redactor.redact("a@b.example.org $token 10.1.2.3 token=abc12345")

        assertEquals(once, redactor.redact(once))
    }

    @Test
    fun `a secret too short to be one is not used to cut the text up`() {
        assertEquals("a b c", ReportRedactor(secrets = listOf("a", "")).redact("a b c"))
    }
}
