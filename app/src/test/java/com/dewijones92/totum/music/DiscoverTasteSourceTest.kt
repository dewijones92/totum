package com.dewijones92.totum.music

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.discover.DiscoverArtist
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.pins.Pin
import com.dewijones92.totum.stats.ListenKind
import com.dewijones92.totum.stats.StatEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DiscoverTasteSourceTest {

    private val now = 100L * 24 * 60 * 60 * 1000
    private val day = 24L * 60 * 60 * 1000

    private fun playable(
        id: String,
        author: String,
        kind: MediaContentKind = MediaContentKind.MUSIC,
        channel: String? = null,
    ) =
        PlayableItem(
            MediaItem(
                MediaItemId(id),
                SourceId("music"),
                "title $id",
                publishedAt = null,
                duration = null,
                author = author,
                contentKind = kind,
                sourceUrl = channel?.let { HttpUrl.of("https://www.youtube.com/channel/$it") },
            ),
            PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
        )

    private fun listen(author: String, ageDays: Long, heardMs: Long = 120_000, kind: ListenKind = ListenKind.MUSIC) =
        StatEvent.Listen(
            at = now - ageDays * day,
            playId = "p$author$ageDays",
            itemId = "i",
            title = "t",
            author = author,
            kind = kind,
            heardMs = heardMs,
        )

    private fun source(
        history: List<PlayableItem> = emptyList(),
        starred: List<PlayableItem> = emptyList(),
        listens: List<StatEvent> = emptyList(),
        recent: List<Pin> = emptyList(),
        followed: FollowedArtists = FollowedArtists(),
    ) = DiscoverTasteSource({ history }, { starred }, { listens }, { recent }, { followed }, { now })

    private fun artist(name: String, id: String = "UC-none") = DiscoverArtist(id, name)

    @Test
    fun `an artist in the play history is recent, by channel id or by each name of a joint credit`() = runBlocking {
        val taste = source(
            history = listOf(
                playable("a", "Duo One & Duo Two", channel = "UCone"),
                playable("v", "A Vlogger", MediaContentKind.STANDARD),
            ),
        ).taste()

        assertNotNull(taste.recentWhy(artist("whoever", "UCone")))
        assertNotNull(taste.recentWhy(artist("duo two")))
        assertNull("a video's author is not an artist", taste.recentWhy(artist("A Vlogger")))
    }

    @Test
    fun `only music listened to for long enough, in the last fourteen days, counts`() = runBlocking {
        val taste = source(
            listens = listOf(
                listen("Fresh", ageDays = 2),
                listen("Old", ageDays = 20),
                listen("Skipped", ageDays = 1, heardMs = 5_000),
                listen("Talk", ageDays = 1, kind = ListenKind.PODCAST),
            ),
        ).taste()

        assertNotNull(taste.recentWhy(artist("Fresh")))
        assertNull(taste.recentWhy(artist("Old")))
        assertNull(taste.recentWhy(artist("Skipped")))
        assertNull(taste.recentWhy(artist("Talk")))
    }

    @Test
    fun `the newest forty stars count, and artists in Recently do too`() = runBlocking {
        val stars = (1..45).map { playable("s$it", "Starred $it") }
        val taste = source(
            starred = stars,
            recent = listOf(
                Pin.Artist("UCrecent", "In Recently", null, null),
                Pin.Discover("UCdisc", "Discovered", "seed", null),
            ),
        ).taste()

        assertNull("the oldest star is not recent any more", taste.recentWhy(artist("Starred 1")))
        assertNotNull(taste.recentWhy(artist("Starred 45")))
        assertEquals("in Recently", taste.recentWhy(artist("x", "UCrecent")))
        assertEquals("in Recently", taste.recentWhy(artist("x", "UCdisc")))
    }

    @Test
    fun `followed artists are known by id and by name`() = runBlocking {
        val taste = source(followed = FollowedArtists(setOf("UCfollow"), setOf("Library Artist"))).taste()

        assertNotNull(taste.followedWhy(artist("x", "UCfollow")))
        assertNotNull(taste.followedWhy(artist("library artist")))
        assertNull(taste.followedWhy(artist("stranger")))
    }

    @Test
    fun `the taste is read once and kept for a few minutes`() = runBlocking {
        var reads = 0
        var clock = now
        val source = DiscoverTasteSource(
            history = {
                reads++
                emptyList()
            },
            starred = { emptyList() },
            listens = { emptyList() },
            recentActions = { emptyList() },
            followed = { FollowedArtists() },
            clock = { clock },
        )

        source.taste()
        clock += 60_000
        source.taste()
        clock += 10 * 60_000
        source.taste()

        assertEquals(2, reads)
    }
}
