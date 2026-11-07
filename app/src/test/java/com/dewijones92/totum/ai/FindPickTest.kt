package com.dewijones92.totum.ai

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.common.Page
import com.dewijones92.totum.data.ask.AskCopies
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.podcast.PreviewResult
import com.dewijones92.totum.data.podcast.fake.FakePodcastRepository
import com.dewijones92.totum.data.search.SearchHit
import com.dewijones92.totum.data.search.SearchOutcome
import com.dewijones92.totum.data.search.SearchSource
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.SourceId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FindPickTest {

    private val taste = Taste(channels = listOf("Kurzgesagt"), shows = listOf("In Our Time"))
    private val searched = mutableListOf<String>()
    private val podcasts = FakePodcastRepository()

    private fun video(
        id: String,
        title: String,
        channel: String,
        kind: MediaContentKind = MediaContentKind.STANDARD,
        membersOnly: Boolean = false,
    ) = SearchHit.Video(
        title = title,
        subtitle = channel,
        artworkUrl = null,
        watchUrl = HttpUrl.of("https://www.youtube.com/watch?v=$id"),
        durationSeconds = 600,
        membersOnly = membersOnly,
        kind = kind,
    )

    private fun episode(id: String, title: String, at: String) =
        MediaItem(MediaItemId(id), SourceId("new"), title, Instant.parse(at), duration = null)

    private fun show(title: String, feed: String) = SearchHit.Podcast(title, null, null, HttpUrl.of(feed))

    private fun copies(vararg hits: SearchHit) = AskCopies({
        listOf(
            SearchSource { query, _, _ ->
                searched += query.value
                SearchOutcome.Success(Page.last(hits.toList()))
            },
        )
    })

    private fun pick(kind: AskKind, words: String = "dragons") =
        Suggestion(kind, "A pick", year = null, why = "w", searchWords = words)

    @Test
    fun `the first video that is not from a followed channel is the pick`() = runTest {
        val found = findPick(
            copies(
                video("aaaaaaaaaa1", "From a followed channel", "Kurzgesagt"),
                video("bbbbbbbbbb2", "From someone new", "Veritasium"),
            ),
            podcasts,
            pick(AskKind.YOUTUBE),
            taste,
        )

        assertEquals("bbbbbbbbbb2", found?.item?.id?.value)
        assertEquals("From someone new", found?.item?.title)
        assertEquals(listOf("dragons"), searched)
    }

    @Test
    fun `a followed channel is matched ignoring case and punctuation`() = runTest {
        val found = findPick(
            copies(video("aaaaaaaaaa1", "Followed", "  KURZGESAGT!  "), video("bbbbbbbbbb2", "New", "Veritasium")),
            podcasts,
            pick(AskKind.YOUTUBE),
            taste,
        )

        assertEquals("bbbbbbbbbb2", found?.item?.id?.value)
    }

    @Test
    fun `a members-only video, a short and a live stream are skipped`() = runTest {
        val found = findPick(
            copies(
                video("mmmmmmmmmm1", "Members", "New A", membersOnly = true),
                video("ssssssssss2", "Short", "New B", kind = MediaContentKind.SHORT),
                video("llllllllll3", "Live", "New C", kind = MediaContentKind.LIVE),
                video("okokokokok4", "Fine", "New D"),
            ),
            podcasts,
            pick(AskKind.YOUTUBE),
            taste,
        )

        assertEquals("okokokokok4", found?.item?.id?.value)
    }

    @Test
    fun `when every hit is unsuitable there is no pick`() = runTest {
        val found = findPick(
            copies(
                video("aaaaaaaaaa1", "Followed", "Kurzgesagt"),
                video("mmmmmmmmmm1", "Members", "New", membersOnly = true)
            ),
            podcasts,
            pick(AskKind.YOUTUBE),
            taste,
        )

        assertNull(found)
        assertNull(findPick(copies(), podcasts, pick(AskKind.YOUTUBE), taste))
    }

    @Test
    fun `a show's pick is its newest episode, and a followed show is skipped`() = runTest {
        val feed = HttpUrl.of("https://feed.example/new.xml")
        val source = MediaSource.PodcastFeed(SourceId("new"), "New Show", feed)
        val older = episode("old", "Older", "2026-01-01T00:00:00Z")
        val newer = episode("new", "Newer", "2026-10-01T00:00:00Z")
        podcasts.previews[feed] = PreviewResult.Loaded(source, listOf(older, newer))

        val found = findPick(
            copies(show("In Our Time", "https://feed.example/followed.xml"), show("New Show", feed.value)),
            podcasts,
            pick(AskKind.PODCAST),
            taste,
        )

        assertEquals("new", found?.item?.id?.value)
    }

    @Test
    fun `a feed that will not load is no pick`() = runTest {
        val found = findPick(
            copies(show("New Show", "https://feed.example/broken.xml")),
            podcasts,
            pick(AskKind.PODCAST),
            taste,
        )

        assertNull(found)
    }

    @Test
    fun `a pick of any other kind is not looked for at all`() = runTest {
        val found = findPick(copies(video("aaaaaaaaaa1", "x", "y")), podcasts, pick(AskKind.SONG), taste)

        assertNull(found)
        assertTrue(searched.isEmpty())
    }
}
