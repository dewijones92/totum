package com.dewijones92.totum.data.ask.state

import com.dewijones92.totum.common.ReportRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StateScrubTest {

    private fun clean(json: String): StateScrub.Scrubbed = StateScrub.clean(Json.parseToJsonElement(json))

    @Test
    fun `an object key that names a secret is dropped with its value`() {
        val result = clean(
            """{"title":"x","accessToken":"a","refresh_token":"b","API-KEY":"c","password":"d","Authorization":"e",""" +
                """"cookie":"f","nested":{"clientSecret":"g","keep":"h"}}""",
        )
        val out = result.element.jsonObject
        assertEquals(setOf("title", "nested"), out.keys)
        assertEquals(setOf("keep"), out.getValue("nested").jsonObject.keys)
        assertEquals(7, result.droppedKeys)
    }

    @Test
    fun `values shaped like secrets are redacted wherever they sit`() {
        val secrets = listOf(
            "Authorization: Bearer abcdef0123456789",
            "bearer abcdef0123456789xyz",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk",
            "ya29.a0AfH6SMBxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
            "1//0gAbCdEfGhIjKlMnOpQrStUvWxYz0123456789",
            "AIzaSyA-1234567890abcdefghijklmnopqrstuv",
            "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
            "token=abcdef123456 and more",
            "x-totum-token: abcdef123456",
            "password: hunter2hunter2",
            "https://admin:pass1234@192.0.2.10/feed",
            "hash 0123456789abcdef0123456789abcdef01234567 here",
        )
        secrets.forEach { text ->
            val out = StateScrub.IDENTIFIERS.redact(text)
            assertTrue("\"$text\" -> \"$out\"", out != text)
            assertFalse(
                "\"$text\" -> \"$out\"",
                Regex("abcdef0123456789|hunter2|pass1234|abcdef123456\\b").containsMatchIn(out)
            )
        }
    }

    @Test
    fun `a link keeps only the parts that name a video, never a signature or key`() {
        val out = StateScrub.IDENTIFIERS.redact(
            "see https://rr1.googlevideo.com/videoplayback?expire=1&sig=SECRETSIG&id=o-ABC and " +
                "https://www.youtube.com/watch?v=abcDEF12345&list=PL1&t=30 and " +
                "https://feeds.example/p.rss?key=SECRETKEY",
        )
        assertFalse(out.contains("SECRETSIG"))
        assertFalse(out.contains("SECRETKEY"))
        assertTrue(out.contains("https://www.youtube.com/watch?v=abcDEF12345&list=PL1&t=30"))
        assertTrue(out.contains("https://feeds.example/p.rss"))
    }

    @Test
    fun `a torrent item id with its info hash is not mistaken for a secret`() {
        val id = "torrent:0123456789abcdef0123456789abcdef01234567:12"
        assertEquals(id, StateScrub.IDENTIFIERS.redact(id))
        assertEquals(
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            StateScrub.IDENTIFIERS.redact("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")
        )
    }

    @Test
    fun `ordinary titles and numbers pass through untouched`() {
        val text = "Day 2 in Hong Kong featuring Eala | WTA Match Highlights 2026-11-06T21:10:51Z 12:34 " +
            "Chopin Op. 10 No. 4"
        assertEquals(text, StateScrub.IDENTIFIERS.redact(text))
        val tree = clean("""{"n":5,"b":true,"t":"$text","l":[1,2,"abc"]}""")
        assertEquals(0, tree.redactedValues)
        assertEquals(text, (tree.element as JsonObject).getValue("t").jsonPrimitive.content)
    }

    @Test
    fun `looksSecret flags the shapes an answer must never carry`() {
        assertTrue(ReportRedactor.looksSecret("here you go: ya29.a0AfH6SMBxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"))
        assertFalse(ReportRedactor.looksSecret("You have 13 starred items, nine played."))
    }
}
