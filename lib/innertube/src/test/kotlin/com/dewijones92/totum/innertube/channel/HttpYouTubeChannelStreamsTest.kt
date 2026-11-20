package com.dewijones92.totum.innertube.channel

import com.dewijones92.totum.common.PageToken
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpYouTubeChannelStreamsTest {

    private val server = MockWebServer()
    private lateinit var channel: HttpYouTubeChannel

    @Before
    fun start() {
        server.start()
        channel = HttpYouTubeChannel(
            InnerTubeClient(
                client = OkHttpClient(),
                browseUrl = server.url("/browse").toString(),
                nextUrl = server.url("/next").toString(),
                searchUrl = server.url("/search").toString(),
            ),
        )
    }

    @After
    fun stop() = server.close()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "fixture $name missing" }
            .bufferedReader().readText()

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    private fun page(result: ChannelVideos) = (result as ChannelVideos.Success).page

    @Test
    fun `a real Live tab response parses to its streams with a continuation`() = runBlocking {
        answer(fixture("channel_streams_web_sample.json"))

        val streams = page(channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ"))

        assertEquals(
            listOf("atn53OrYlZU", "eYly4OT2pjs", "tKMuSD-DaOU", "4_IvuKKed8s", "PomvTPe0cvI", "Ds9ENF11q4o"),
            streams.items.map { it.videoId },
        )
        assertTrue("every stream is titled", streams.items.all { it.title.isNotBlank() })
        assertTrue("every stream says when it streamed", streams.items.all { it.publishedText != null })
        assertNotNull("the tab has more than one page", streams.next)
    }

    @Test
    fun `the Live tab is asked for with its own params`() = runBlocking {
        answer(fixture("channel_streams_web_sample.json"))

        channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ")

        val sent = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(sent, sent.contains("\"params\":\"EgdzdHJlYW1z8gYECgJ6AA==\""))
        assertTrue(sent, sent.contains("\"browseId\":\"UC9ZPFOiLoEeOBJseKICaFFQ\""))
    }

    @Test
    fun `a channel with no Live tab gets another tab's videos back and lists none of them as streams`() = runBlocking {
        answer(fixture("channel_streams_absent_web_sample.json"))

        val streams = page(channel.streams("UCMJNxk066Acu8AFoUNNFoqQ"))

        assertTrue(
            "the Videos tab's rows are not streams: ${streams.items.map { it.videoId }}",
            streams.items.isEmpty()
        )
        assertNull(streams.next)
    }

    @Test
    fun `a response whose selected tab is Home lists no streams either`() = runBlocking {
        answer(
            """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[
              {"tabRenderer":{"title":"Home","selected":true,"endpoint":{"commandMetadata":{"webCommandMetadata":
                {"url":"/@someone/featured"}}},"content":{"sectionListRenderer":{"contents":[
                  {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentId":"aaaaaaaaaaa",
                   "metadata":{"lockupMetadataViewModel":{"title":{"content":"Featured"}}}}}]}}}},
              {"tabRenderer":{"title":"Videos","endpoint":{"commandMetadata":{"webCommandMetadata":
                {"url":"/@someone/videos"}}}}}]}}}""",
        )

        assertTrue(page(channel.streams("UCsomeone")).items.isEmpty())
    }

    @Test
    fun `a later page sends the continuation and is not judged by its tabs`() = runBlocking {
        answer(
            """{"contents":{"items":[{"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO",""" +
                """"contentId":"bbbbbbbbbbb","metadata":{"lockupMetadataViewModel":""" +
                """{"title":{"content":"Older"}}}}}]}}""",
        )

        val streams = page(channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ", after = PageToken("streams-2")))

        assertEquals(listOf("bbbbbbbbbbb"), streams.items.map { it.videoId })
        val sent = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(sent, sent.contains("\"continuation\":\"streams-2\""))
        assertFalse(sent, sent.contains("\"params\""))
    }

    @Test
    fun `a Videos page that lists no Live tab means the streams are never asked for`() = runBlocking {
        answer(fixture("channel_streams_absent_web_sample.json"))
        channel.videos("UCMJNxk066Acu8AFoUNNFoqQ")

        val streams = page(channel.streams("UCMJNxk066Acu8AFoUNNFoqQ"))

        assertTrue(streams.items.isEmpty())
        assertEquals("only the Videos page was fetched", 1, server.requestCount)
    }

    @Test
    fun `a Videos page that lists a Live tab lets the streams be asked for`() = runBlocking {
        answer(fixture("channel_videos_with_live_tab_web_sample.json"))
        answer(fixture("channel_streams_web_sample.json"))
        channel.videos("UC9ZPFOiLoEeOBJseKICaFFQ")

        val streams = page(channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ"))

        assertEquals("atn53OrYlZU", streams.items.first().videoId)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a channel nobody has looked at is asked, and what comes back is remembered`() = runBlocking {
        answer(fixture("channel_streams_absent_web_sample.json"))

        channel.streams("UCMJNxk066Acu8AFoUNNFoqQ")
        channel.streams("UCMJNxk066Acu8AFoUNNFoqQ")

        assertEquals("the second ask is answered from what the first learned", 1, server.requestCount)
    }

    @Test
    fun `a channel that grows a Live tab is asked again once its Videos page says so`() = runBlocking {
        answer(fixture("channel_streams_absent_web_sample.json"))
        answer(fixture("channel_videos_with_live_tab_web_sample.json"))
        answer(fixture("channel_streams_web_sample.json"))
        channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ")
        assertTrue(page(channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ")).items.isEmpty())

        channel.videos("UC9ZPFOiLoEeOBJseKICaFFQ")

        assertEquals("atn53OrYlZU", page(channel.streams("UC9ZPFOiLoEeOBJseKICaFFQ")).items.first().videoId)
    }
}
