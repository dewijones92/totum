package com.dewijones92.totum.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A stream whose URL carries a deciphered `n` is preferred, because it is the one that plays to
 * the end.
 *
 * On 2026-08-18 YouTube began serving roughly the first megabyte of a stream and refusing the rest,
 * and Dewi's app could play nothing it had not downloaded. Measured from his own connection, on fresh
 * URLs, the difference between a stream that dies and one that does not is visible in the URL itself:
 *
 * | Client | `n=` in the URL | A range 50% into the file |
 * |---|---|---|
 * | `ANDROID_VR` — yt-dlp's only default | no | **403**, every time |
 * | `WEB_EMBEDDED_PLAYER` — needs a JS runtime to solve `n` | yes | 206 on 2 of the 3 videos tried |
 *
 * `n` is the parameter a JavaScript runtime exists to solve; a URL that has one has been through that
 * solve, and YouTube treats it as a real client's request. The app already runs QuickJS for extraction
 * (deliberately — see `totum_ytdlp.py`), so the durable URLs were available all along and simply never
 * asked for: `player_client` listed `default` and `android`, and neither yields them.
 *
 * Adding the client is not enough on its own, which is why this rule exists. With every client
 * requested at once, yt-dlp's own ranking still handed back `ANDROID_VR`'s audio — it is a fine format
 * by every measure the ranking knows about, and unfetchable past its first megabyte. Durability is not
 * something a bitrate can express, so it is asserted here as the first thing that matters.
 *
 * A partial fix, honestly: one of the three videos was refused even via `WEB_EMBEDDED_PLAYER`. The full
 * answer is a PO token — see `docs/todos/youtube-requires-attestation.md`.
 */
class DurableStreamsWinTest {

    private fun audio(itag: Int, size: Long, url: String) = MediaFormat(
        formatId = "$itag",
        container = "webm",
        width = null,
        height = null,
        hasVideo = false,
        hasAudio = true,
        fileSizeBytes = size,
        url = url,
        videoCodec = null,
        audioCodec = "opus",
    )

    private fun metadata(vararg formats: MediaFormat) = MediaMetadata(
        id = "v",
        title = "v",
        uploader = null,
        durationSeconds = 2202,
        thumbnailUrl = null,
        formats = formats.toList(),
    )

    /** THE case. The bigger stream is the capped one, so size alone picks the broken option. */
    @Test
    fun `an audio stream with a deciphered n beats a larger one without`() {
        val meta = metadata(
            audio(251, size = 32_000_000, url = "$HOST?itag=251&c=ANDROID_VR&clen=32000000"),
            audio(250, size = 16_000_000, url = "$HOST?itag=250&c=WEB_EMBEDDED_PLAYER&n=abc123&clen=16000000"),
        )

        assertEquals("250", meta.bestAudioFormat()?.formatId)
    }

    /** With none of them durable, the old rule stands: the best of what there is. */
    @Test
    fun `among equally undurable streams the largest still wins`() {
        val meta = metadata(
            audio(251, size = 32_000_000, url = "$HOST?itag=251&c=ANDROID_VR"),
            audio(250, size = 16_000_000, url = "$HOST?itag=250&c=ANDROID_VR"),
        )

        assertEquals("251", meta.bestAudioFormat()?.formatId)
    }

    /** And among durable ones, likewise — durability is a gate, not a whole ordering. */
    @Test
    fun `among durable streams the largest wins`() {
        val meta = metadata(
            audio(251, size = 32_000_000, url = "$HOST?itag=251&n=aaa"),
            audio(250, size = 16_000_000, url = "$HOST?itag=250&n=bbb"),
        )

        assertEquals("251", meta.bestAudioFormat()?.formatId)
    }

    /**
     * `n` must be the whole parameter name. A URL carrying `ns=` or `sn=` has not been through any
     * solve, and treating it as durable would prefer exactly the streams that die.
     */
    @Test
    fun `a similarly-named parameter does not count as durable`() {
        val meta = metadata(
            audio(251, size = 16_000_000, url = "$HOST?itag=251&ns=xyz&sn=abc"),
            audio(250, size = 8_000_000, url = "$HOST?itag=250&n=real"),
        )

        assertEquals("250", meta.bestAudioFormat()?.formatId)
    }

    /** A non-YouTube URL has no `n` and never will; it must not be ranked below anything. */
    @Test
    fun `a podcast enclosure is unaffected`() {
        val meta = metadata(audio(0, size = 5_000_000, url = "https://media.test/episode.mp3"))

        assertEquals("0", meta.bestAudioFormat()?.formatId)
    }

    private companion object {
        const val HOST = "https://rr1---sn-abc.googlevideo.com/videoplayback"
    }
}
