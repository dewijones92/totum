package com.dewijones92.totum.domain

import com.dewijones92.totum.common.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareLinkTest {

    private val watch = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
    private val home = "pi.home.example"

    private fun item(
        id: String = "x",
        source: String = "feed",
        mediaUrl: String? = null,
    ) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId(source),
        title = "A title",
        publishedAt = null,
        duration = null,
        mediaUrl = mediaUrl?.let(HttpUrl::of),
    )

    private fun linkOf(item: MediaItem, homeHost: String? = home): String? =
        (item.shareLink(homeHost) as? ShareLink.Link)?.url?.value

    @Test
    fun `a YouTube video shares its canonical watch address whatever the address it carries`() {
        assertEquals(watch, linkOf(item(id = watch, mediaUrl = watch)))
        assertEquals(watch, linkOf(item(id = "dQw4w9WgXcQ", mediaUrl = "https://youtu.be/dQw4w9WgXcQ?si=tracking")))
        assertEquals(
            watch,
            linkOf(item(id = "dQw4w9WgXcQ", mediaUrl = "https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=90"))
        )
    }

    @Test
    fun `a Short, a live address and an embed all become the watch address`() {
        assertEquals(watch, linkOf(item(mediaUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ")))
        assertEquals(watch, linkOf(item(mediaUrl = "https://www.youtube.com/live/dQw4w9WgXcQ?feature=share")))
        assertEquals(watch, linkOf(item(mediaUrl = "https://www.youtube.com/embed/dQw4w9WgXcQ")))
    }

    @Test
    fun `an item known only by a watch address as its id still shares it`() {
        val shared = item(id = watch, mediaUrl = null)

        assertEquals(watch, linkOf(shared))
    }

    @Test
    fun `a podcast episode shares its own media address`() {
        assertEquals(
            "https://feeds.example.com/ep1.mp3",
            linkOf(item(mediaUrl = "https://feeds.example.com/ep1.mp3")),
        )
    }

    @Test
    fun `a torrent shares its title only, by source or by id, even when it has an address`() {
        val bySource = item(source = "torrent", mediaUrl = "https://pi.home.example/stream/1")
        val byId = item(id = "torrent:abc", mediaUrl = "https://elsewhere.example/stream/1")

        assertTrue(bySource.shareLink(home) is ShareLink.TitleOnly)
        assertTrue(byId.shareLink(home) is ShareLink.TitleOnly)
        assertTrue(bySource.shareLink(null) is ShareLink.TitleOnly)
    }

    @Test
    fun `an address on the Pi is private to this phone so only the title is shared`() {
        assertTrue(item(mediaUrl = "https://pi.home.example/files/a.mp3").shareLink(home) is ShareLink.TitleOnly)
        assertTrue(item(mediaUrl = "https://PI.HOME.EXAMPLE/files/a.mp3").shareLink(home) is ShareLink.TitleOnly)
    }

    @Test
    fun `an address on another host is shared and a missing home host shares everything that has an address`() {
        val elsewhere = item(mediaUrl = "https://cdn.example.com/a.mp3")

        assertEquals("https://cdn.example.com/a.mp3", linkOf(elsewhere))
        assertEquals(
            "https://pi.home.example/files/a.mp3",
            linkOf(item(mediaUrl = "https://pi.home.example/files/a.mp3"), homeHost = null)
        )
    }

    @Test
    fun `no address at all shares the title only`() {
        assertTrue(item().shareLink(home) is ShareLink.TitleOnly)
    }

    @Test
    fun `every answer says why, so a report can explain a missing link`() {
        listOf(
            item(mediaUrl = watch),
            item(source = "torrent"),
            item(),
            item(mediaUrl = "https://pi.home.example/a.mp3"),
            item(mediaUrl = "https://cdn.example.com/a.mp3"),
        ).forEach { assertTrue(it.shareLink(home).why.isNotBlank()) }
    }

    @Test
    fun `the message is the title and the link on the next line, or the title alone`() {
        val link = ShareLink.Link(HttpUrl.of(watch), "why")

        assertEquals("A title\n$watch", link.messageFor("A title"))
        assertEquals("A title", ShareLink.TitleOnly("why").messageFor("A title"))
    }
}
