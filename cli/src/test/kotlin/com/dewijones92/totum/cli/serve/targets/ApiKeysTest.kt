package com.dewijones92.totum.cli.serve.targets

import org.junit.Assert.assertEquals
import org.junit.Test

class ApiKeysTest {

    @Test
    fun `a key in a URL is hidden wherever it sits, and the rest of the text is kept`() {
        assertEquals(
            "http://jellyfin.local/Videos/abc/stream?static=true&api_key=***&x=1",
            "http://jellyfin.local/Videos/abc/stream?static=true&api_key=s3cr3t&x=1".withoutApiKeys(),
        )
        assertEquals(
            "mpv: failed to open http://j/a?api_key=*** | exit 2",
            "mpv: failed to open http://j/a?api_key=s3cr3t | exit 2".withoutApiKeys(),
        )
        assertEquals(
            "[http @ 0x1] HTTP error 401 at ?ApiKey=***",
            "[http @ 0x1] HTTP error 401 at ?ApiKey=abc".withoutApiKeys()
        )
    }

    @Test
    fun `text with no key is untouched`() {
        assertEquals("nothing secret here", "nothing secret here".withoutApiKeys())
    }
}
