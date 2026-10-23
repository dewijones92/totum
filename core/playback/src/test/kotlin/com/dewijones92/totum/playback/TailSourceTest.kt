package com.dewijones92.totum.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class TailSourceTest {

    private val stream = "https://rr1.googlevideo.test/videoplayback?itag=140"

    private fun of(uri: String?, audioOnly: Boolean = true, unmetered: Boolean = true, playedMs: Long = 20_000) =
        tailSourceOf(uri, audioOnly, unmetered, playedMs)

    @Test
    fun `a file on the phone is scanned at once whatever the network`() {
        assertEquals(TailSource.File("/data/x/a.m4a"), of("file:///data/x/a.m4a", unmetered = false, playedMs = 0))
    }

    @Test
    fun `an audio-only stream on Wi-Fi is scanned once it has played a while`() {
        assertEquals(TailSource.NotYet, of(stream, playedMs = 3_000))
        assertEquals(TailSource.Stream(stream), of(stream, playedMs = 10_000))
    }

    @Test
    fun `a stream with a picture, on mobile data, or not over http is left to the silent tail`() {
        assertEquals(true, of(stream, audioOnly = false) is TailSource.None)
        assertEquals(true, of(stream, unmetered = false) is TailSource.None)
        assertEquals(true, of("content://media/1") is TailSource.None)
        assertEquals(true, of(null) is TailSource.None)
    }

    @Test
    fun `a playlist or manifest is not something MediaExtractor can read, so it is left alone`() {
        assertEquals(true, of("https://home.test/audio/abc/7/index.m3u8?totumToken=x") is TailSource.None)
        assertEquals(true, of("http://127.0.0.1:8080/live/manifest.mpd") is TailSource.None)
    }
}
