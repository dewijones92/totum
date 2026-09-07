package com.dewijones92.totum.video

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.innertube.player.PlayableFormat
import com.dewijones92.totum.innertube.player.PlayerClient
import com.dewijones92.totum.innertube.player.PlayerDetails
import com.dewijones92.totum.innertube.player.StreamingData
import com.dewijones92.totum.sabr.SabrClientInfo
import com.dewijones92.totum.sabr.SabrSessions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A SABR endpoint is bound to the client that asked for it, so the session must remember which one —
 * the embedded endpoint refused every request that did not declare it (measured 2026-09-06).
 */
class SabrSessionDeclaresItsClientTest {

    @After fun tearDown() = SabrSessions.clear()

    @Test
    fun `an embedded response registers a session declaring the embedded client`() {
        assertNotNull(SabrResolve.prepare(VIDEO_ID, streamingData(), details(), client = PlayerClient.EMBEDDED))
        assertEquals(SabrClientInfo.WEB_EMBEDDED, SabrSessions.of(VIDEO_ID)?.clientInfo)
    }

    @Test
    fun `an ANDROID response keeps the proven undeclared shape`() {
        assertNotNull(SabrResolve.prepare(VIDEO_ID, streamingData(), details()))
        assertNull(SabrSessions.of(VIDEO_ID)?.clientInfo)
    }

    private fun streamingData() = StreamingData(
        formats = listOf(
            PlayableFormat(
                itag = 251,
                mimeType = "audio/webm; codecs=\"opus\"",
                height = null,
                bitrate = 160_000,
                url = null,
                lastModified = 1L
            ),
        ),
        serverAbrStreamingUrl = HttpUrl.of("https://sabr.test/videoplayback"),
        ustreamerConfig = byteArrayOf(1, 2, 3),
    )

    private fun details() = PlayerDetails(
        videoId = VIDEO_ID,
        title = "A video",
        author = null,
        channelId = null,
        lengthSeconds = 600,
        thumbnailUrl = null,
        description = null,
    )

    private companion object {
        const val VIDEO_ID = "aqz-KE-bpKQ"
    }
}
