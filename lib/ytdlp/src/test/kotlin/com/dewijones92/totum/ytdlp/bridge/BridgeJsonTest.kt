package com.dewijones92.totum.ytdlp.bridge

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.ytdlp.ChannelResult
import com.dewijones92.totum.ytdlp.DownloadEvent
import com.dewijones92.totum.ytdlp.ExtractionResult
import com.dewijones92.totum.ytdlp.VideoSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeJsonTest {

    private val url = HttpUrl.of("https://example.com/watch?v=abc")

    @Test
    fun `parses versions`() {
        val versions = parseVersions("""{"yt_dlp":"2026.07.04","python":"3.13.2"}""")
        assertEquals("2026.07.04", versions.ytDlp)
        assertEquals("3.13.2", versions.python)
    }

    @Test
    fun `parses successful extraction with mixed formats`() {
        val text = """
            {"ok": true, "info": {
                "id": "abc", "title": "A video", "uploader": "A channel",
                "duration": 90.5, "thumbnail": "https://i.example.com/t.jpg",
                "description": "Line one\nLine two",
                "formats": [
                    {"format_id": "22", "ext": "mp4", "vcodec": "avc1", "acodec": "mp4a",
                     "width": 1280, "height": 720, "filesize": 1000,
                     "url": "https://cdn.example.com/22.mp4"},
                    {"format_id": "140", "ext": "m4a", "vcodec": "none", "acodec": "mp4a",
                     "width": null, "height": null, "filesize_approx": 250},
                    {"format_id": "sb0", "ext": "mhtml", "vcodec": "none", "acodec": "none"},
                    {"ext": "mp4", "vcodec": "avc1"}
                ]
            }}
        """.trimIndent()

        val metadata = (parseExtraction(url, text) as ExtractionResult.Success).metadata

        assertEquals("abc", metadata.id)
        assertEquals(90L, metadata.durationSeconds)
        assertEquals("Line one\nLine two", metadata.description)
        // Formats with no format_id or no codecs at all (storyboards) are dropped, not crashed on.
        assertEquals(2, metadata.formats.size)
        val (video, audio) = metadata.formats
        assertEquals(1280, video.width)
        assertEquals(false, video.isAudioOnly)
        assertEquals(1000L, video.fileSizeBytes)
        assertEquals("https://cdn.example.com/22.mp4", video.url)
        assertTrue(audio.isAudioOnly)
        assertNull(audio.width)
        assertNull(audio.url)
        assertEquals(250L, audio.fileSizeBytes)
    }

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=abc")

    private fun metadataOf(info: String) =
        (parseExtraction(watch, """{"ok": true, "info": $info}""") as ExtractionResult.Success).metadata

    @Test
    fun `the upload timestamp becomes a publication instant`() {
        val metadata = metadataOf("""{"id": "abc", "title": "A video", "timestamp": 1741046400, "formats": []}""")
        assertEquals(java.time.Instant.parse("2025-03-04T00:00:00Z"), metadata.publishedAt)
    }

    @Test
    fun `an upload_date alone gives the day, and nothing gives null`() {
        val dated = metadataOf("""{"id": "abc", "title": "A video", "upload_date": "20250304", "formats": []}""")
        assertEquals(java.time.Instant.parse("2025-03-04T00:00:00Z"), dated.publishedAt)
        val bare = metadataOf("""{"id": "abc", "title": "A video", "formats": []}""")
        assertEquals(null, bare.publishedAt)
    }

    @Test
    fun `parses chapters, dropping ones missing a start or title`() {
        val text = """
            {"ok": true, "info": {
                "id": "abc", "title": "A video",
                "chapters": [
                    {"start_time": 0.0, "end_time": 30.0, "title": "Intro"},
                    {"start_time": 30.0, "title": "Main"},
                    {"end_time": 60.0, "title": "no start"},
                    {"start_time": 90.0}
                ],
                "formats": []
            }}
        """.trimIndent()

        val metadata = (parseExtraction(url, text) as ExtractionResult.Success).metadata

        assertEquals(2, metadata.chapters.size)
        assertEquals(0.0, metadata.chapters[0].startSeconds, 0.0)
        assertEquals("Intro", metadata.chapters[0].title)
        assertEquals(30.0, metadata.chapters[1].startSeconds, 0.0)
        assertEquals("Main", metadata.chapters[1].title)
    }

    @Test
    fun `a present but null chapters or formats value yields empty lists, not a crash`() {
        // yt-dlp sets "chapters": null for the majority of videos (no chapters);
        // the key is present with a JSON null, which must not throw.
        val text = """
            {"ok": true, "info": {
                "id": "abc", "title": "No chapters", "chapters": null, "formats": null
            }}
        """.trimIndent()

        val metadata = (parseExtraction(url, text) as ExtractionResult.Success).metadata

        assertTrue(metadata.chapters.isEmpty())
        assertTrue(metadata.formats.isEmpty())
    }

    @Test
    fun `parses search entries, dropping ones without id or url`() {
        val text = """
            {"ok": true, "entries": [
                {"id": "v1", "title": "First", "uploader": "Chan", "duration": 61.0,
                 "url": "https://www.youtube.com/watch?v=v1",
                 "thumbnail": "https://i.example.com/v1.jpg"},
                {"id": "v2", "url": "not a url"},
                {"title": "no id", "url": "https://example.com/x"}
            ]}
        """.trimIndent()

        val entries = (parseSearch(text) as VideoSearchResult.Success).entries

        assertEquals(1, entries.size)
        assertEquals("v1", entries[0].id)
        assertEquals("First", entries[0].title)
        assertEquals(61L, entries[0].durationSeconds)
        assertEquals("https://www.youtube.com/watch?v=v1", entries[0].watchUrl.value)
    }

    @Test
    fun `search failure maps to a value`() {
        val result = parseSearch("""{"ok": false, "kind": "network", "detail": "offline"}""")
        assertEquals(VideoSearchResult.Failure("offline"), result)
    }

    @Test
    fun `parses channel with title and videos`() {
        val text = """
            {"ok": true, "channel_id": "UC1", "title": "My Channel", "videos": [
                {"id": "v1", "title": "Vid 1", "url": "https://www.youtube.com/watch?v=v1"},
                {"id": "v2", "url": "not a url"}
            ]}
        """.trimIndent()

        val channel = parseChannel(url, text) as ChannelResult.Success

        assertEquals("My Channel", channel.title)
        assertEquals(1, channel.videos.size)
        assertEquals("v1", channel.videos[0].id)
    }

    @Test
    fun `channel failures map to values`() {
        assertTrue(
            parseChannel(url, """{"ok": false, "kind": "network", "detail": "x"}""")
            is ChannelResult.Failure.Network,
        )
        assertTrue(
            parseChannel(url, """{"ok": false, "kind": "not_channel", "detail": "x"}""")
            is ChannelResult.Failure.NotAChannel,
        )
    }

    @Test
    fun `missing optional metadata degrades to defaults`() {
        val metadata = (parseExtraction(url, """{"ok": true, "info": {}}""") as ExtractionResult.Success).metadata
        assertEquals(url.value, metadata.id)
        assertEquals("Untitled", metadata.title)
        assertNull(metadata.durationSeconds)
        assertNull(metadata.description)
        assertTrue(metadata.formats.isEmpty())
    }

    @Test
    fun `failure kinds map to sealed failures`() {
        assertEquals(
            ExtractionResult.Failure.UnsupportedUrl(url),
            parseExtraction(url, """{"ok": false, "kind": "unsupported", "detail": "x"}"""),
        )
        assertEquals(
            ExtractionResult.Failure.Network("timed out"),
            parseExtraction(url, """{"ok": false, "kind": "network", "detail": "timed out"}"""),
        )
        assertEquals(
            ExtractionResult.Failure.Extractor("geo"),
            parseExtraction(url, """{"ok": false, "kind": "extractor", "detail": "geo"}"""),
        )
    }

    @Test
    fun `download completion carries the output file`() {
        val event = parseDownloadCompletion(url, """{"ok": true, "filepath": "/sdcard/x.mp4"}""", ::File)
        assertEquals(File("/sdcard/x.mp4"), (event as DownloadEvent.Completed).file)
    }

    @Test
    fun `download failure and missing filepath map to Failed`() {
        assertTrue(
            parseDownloadCompletion(url, """{"ok": false, "kind": "network", "detail": "x"}""", ::File)
            is DownloadEvent.Failed,
        )
        assertTrue(parseDownloadCompletion(url, """{"ok": true}""", ::File) is DownloadEvent.Failed)
    }
}
