package com.dewijones92.totum.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpUrlTest {

    @Test
    fun `parses https url`() {
        assertEquals("https://example.com/feed.xml", HttpUrl.parse("https://example.com/feed.xml")?.value)
    }

    @Test
    fun `parses http url`() {
        assertNotNull(HttpUrl.parse("http://example.com"))
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("https://example.com", HttpUrl.parse("  https://example.com  ")?.value)
    }

    @Test
    fun `rejects other schemes`() {
        assertNull(HttpUrl.parse("ftp://example.com"))
        assertNull(HttpUrl.parse("file:///etc/passwd"))
        assertNull(HttpUrl.parse("javascript:alert(1)"))
        assertNull(HttpUrl.parse("rtmp://example.com/stream"))
    }

    @Test
    fun `rejects relative and malformed input`() {
        assertNull(HttpUrl.parse("example.com"))
        assertNull(HttpUrl.parse("/feed.xml"))
        assertNull(HttpUrl.parse(""))
        assertNull(HttpUrl.parse("https://"))
        assertNull(HttpUrl.parse("ht tp://example.com"))
    }

    @Test
    fun `of throws on invalid input`() {
        assertThrows(IllegalArgumentException::class.java) { HttpUrl.of("not a url") }
    }

    @Test
    fun `host is the machine name without port, path or query`() {
        assertEquals("example.com", HttpUrl.of("https://example.com:8443/feed.xml?x=1").host)
        assertEquals("pi.home.example", HttpUrl.of("http://pi.home.example/audio/a.mp3").host)
    }

    @Test
    fun `host keeps its case so callers compare ignoring case`() {
        assertEquals("Example.COM", HttpUrl.of("https://Example.COM/a").host)
    }

    @Test
    fun `host of an address with an ip is the ip`() {
        assertEquals("192.168.0.20", HttpUrl.of("http://192.168.0.20:8080/x").host)
    }
}
