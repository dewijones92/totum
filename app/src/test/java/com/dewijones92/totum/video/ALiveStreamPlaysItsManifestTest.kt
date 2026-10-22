package com.dewijones92.totum.video

import com.dewijones92.totum.innertube.player.PlayerResponseParser
import com.dewijones92.totum.innertube.player.PlayerResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ALiveStreamPlaysItsManifestTest {

    private val manifest = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/live1/file/index.m3u8"

    private val live = """
        {"playabilityStatus":{"status":"OK"},
         "streamingData":{
           "hlsManifestUrl":"$manifest",
           "adaptiveFormats":[
             {"itag":299,"mimeType":"video/mp4; codecs=\"avc1.64002a\"","width":1920,"height":1080,"bitrate":6000000,
              "url":"https://rr1.googlevideo.com/videoplayback?itag=299&live=1&sq=12345"},
             {"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":128000,
              "url":"https://rr1.googlevideo.com/videoplayback?itag=140&live=1&sq=12345"}]},
         "videoDetails":{"videoId":"live1","title":"synthwave radio","isLiveContent":true}}
    """.trimIndent()

    private val streams = (PlayerResponseParser.parse(live) as PlayerResult.Success).streaming

    @Test
    fun `watching a live stream plays its manifest, not one live segment`() {
        val ladder = streams.videoQualities()
        assertEquals(listOf(manifest), ladder.map { it.videoUrl.value })
        assertEquals(null, ladder.single().audioUrl)
    }

    @Test
    fun `listening to a live stream plays its manifest too`() {
        assertEquals(manifest, streams.bestAudioUrl()?.value)
        assertEquals(manifest, streams.bestMuxedUrl()?.value)
    }
}
