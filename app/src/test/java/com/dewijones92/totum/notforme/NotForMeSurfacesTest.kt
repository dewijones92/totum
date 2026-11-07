package com.dewijones92.totum.notforme

import com.dewijones92.totum.backup.BackupService
import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.notforme.NotForMe
import com.dewijones92.totum.data.notforme.SuppressedItem
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.uploads.LatestUploads
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotForMeSurfacesTest {

    private val channelUrl = "https://www.youtube.com/channel/UCpeepshowpeepshowpeepsh"
    private val channel = MediaSource.VideoChannel(SourceId(channelUrl), "Peep Show", HttpUrl.of(channelUrl))

    private fun entry(id: String, title: String, day: Int) = """
        <entry>
          <yt:videoId>$id</yt:videoId>
          <yt:channelId>UCpeepshowpeepshowpeepsh</yt:channelId>
          <title>$title</title>
          <link rel="alternate" href="https://www.youtube.com/watch?v=$id"/>
          <author><name>Peep Show</name></author>
          <published>2026-10-0${day}T10:00:00+00:00</published>
        </entry>
    """.trimIndent().trim()

    private val feed = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns:yt="http://www.youtube.com/xml/schemas/2015" xmlns="http://www.w3.org/2005/Atom">
          <title>Peep Show</title>
          ${entry("aaaaaaaaaaa", "Mark's day", 3)}
          ${entry("bbbbbbbbbbb", "Jez's day", 2)}
          ${entry("ccccccccccc", "Super Hans", 1)}
        </feed>
    """.trimIndent().trimStart()

    private fun latestUploads(notForMe: NotForMe) = LatestUploads(
        fetcher = { FetchResult.Success(feed) },
        podcasts = FakePodcastRepository(),
        pages = { _, _, _ -> null },
        notForMe = notForMe,
    )

    private fun suppressed(id: String, title: String) =
        MediaItem(MediaItemId(id), SourceId("ch"), title, publishedAt = null, duration = null)

    @Test
    fun `a source's latest uploads leave out what is not for me and keep the rest in order`() = runTest {
        val notForMe = NotForMe()
        notForMe.suppress(suppressed("bbbbbbbbbbb", "Jez's day"), "test")

        val latest = latestUploads(notForMe).of(channel)

        assertEquals(listOf("aaaaaaaaaaa", "ccccccccccc"), latest?.map { it.id.value })
    }

    @Test
    fun `with nothing suppressed every upload is listed`() = runTest {
        val latest = latestUploads(NotForMe()).of(channel)

        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), latest?.map { it.id.value })
    }

    @Test
    fun `a feed that could not be read stays unknown instead of becoming an empty list`() = runTest {
        val broken = LatestUploads(
            fetcher = { FetchResult.Success("not a feed") },
            podcasts = FakePodcastRepository(),
            pages = { _, _, _ -> null },
            notForMe = NotForMe(),
        )

        assertNull(broken.of(channel))
    }

    private class Memory(var values: Map<String, String> = mapOf("other" to "1")) : BackupService.BackupSettings {
        var restored: Map<String, String>? = null
        override fun export() = values
        override fun restore(values: Map<String, String>) {
            restored = values
        }
    }

    @Test
    fun `a backup carries the list beside the other settings`() {
        val notForMe = NotForMe()
        notForMe.suppress(suppressed("aaaaaaaaaaa", "Mark's day"), "test")
        val wrapped = Memory().withNotForMe(notForMe)

        val exported = wrapped.export()

        assertEquals("1", exported["other"])
        assertEquals(notForMe.items.value, NotForMe.decode(exported.getValue("notforme.v1")))
    }

    @Test
    fun `restoring merges what the phone lacks, keeps what it has and still restores the other settings`() {
        val source = NotForMe()
        source.suppress(suppressed("aaaaaaaaaaa", "Mark's day"), "test")
        source.suppress(suppressed("bbbbbbbbbbb", "Jez's day"), "test")
        val inner = Memory()
        val phone = NotForMe()
        phone.suppress(suppressed("ccccccccccc", "Super Hans"), "test")

        inner.withNotForMe(phone).restore(mapOf("other" to "2", "notforme.v1" to source.encode()))

        assertEquals(mapOf("other" to "2", "notforme.v1" to source.encode()), inner.restored)
        assertEquals(
            setOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"),
            phone.items.value.map { it.id }.toSet(),
        )
    }

    @Test
    fun `an unreadable list in a backup is ignored and an absent one leaves the phone's alone`() {
        val phone = NotForMe()
        phone.suppress(suppressed("ccccccccccc", "Super Hans"), "test")
        val inner = Memory()
        val wrapped = inner.withNotForMe(phone)

        wrapped.restore(mapOf("notforme.v1" to "{ nope"))
        wrapped.restore(mapOf("other" to "3"))

        assertEquals(
            listOf(SuppressedItem("ccccccccccc", "Super Hans", null, phone.items.value.single().atMs)),
            phone.items.value
        )
        assertEquals(mapOf("other" to "3"), inner.restored)
    }
}
