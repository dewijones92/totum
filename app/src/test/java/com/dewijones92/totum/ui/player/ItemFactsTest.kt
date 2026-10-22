package com.dewijones92.totum.ui.player

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.AlbumRef
import com.dewijones92.totum.domain.DownloadState
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SkipSegment
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ItemFactsTest {

    private val published = Instant.parse("2024-03-12T10:00:00Z")

    private val state = PlaybackState(
        itemId = MediaItemId("v1"),
        title = "Peep Show S2E5",
        artist = "Channel 4",
        artworkUrl = null,
        isPlaying = true,
        positionMs = 0,
        durationMs = 1_421_000,
        speed = 1f,
        chapters = emptyList(),
    )

    private val listed = MediaItem(
        id = MediaItemId("v1"),
        sourceId = SourceId("yt"),
        title = "Peep Show S2E5",
        publishedAt = published,
        duration = 23.minutes,
        author = "Channel 4 Comedy",
        publisher = "Channel 4",
        viewsText = "1.2M views",
        sourceUrl = HttpUrl.of("https://www.youtube.com/watch?v=v1"),
    )

    @Test
    fun `everything the row knew reaches the player, in a fixed order, blanks left out`() {
        val facts = itemFacts(listed, state, DownloadState.Downloaded("/x/v1.media", audioOnly = true), 48_000_000)

        assertEquals(
            listOf(
                ItemFact.Published(published, null),
                ItemFact.Length(23.minutes.inWholeMilliseconds),
                ItemFact.Views("1.2M views"),
                ItemFact.By("Channel 4 Comedy"),
                ItemFact.Publisher("Channel 4"),
                ItemFact.Kind(MediaContentKind.STANDARD, isVideo = true),
                ItemFact.Offline(audioOnly = true, bytes = 48_000_000),
                ItemFact.Source("www.youtube.com"),
            ),
            facts,
        )
    }

    @Test
    fun `a song says its album, and the counts come from what is playing`() {
        val song = listed.copy(
            contentKind = MediaContentKind.MUSIC,
            album = AlbumRef("a1", "Romero Plays Rodrigo"),
            viewsText = null
        )
        val playing = state.copy(skipSegments = listOf(SkipSegment(0.seconds, 1.seconds)))

        val facts = itemFacts(song, playing, DownloadState.NotDownloaded, null)

        assertEquals(ItemFact.Album("Romero Plays Rodrigo"), facts.single { it is ItemFact.Album })
        assertEquals(ItemFact.Kind(MediaContentKind.MUSIC, isVideo = true), facts.single { it is ItemFact.Kind })
        assertEquals(ItemFact.Skips(1), facts.single { it is ItemFact.Skips })
        assertEquals(emptyList<ItemFact>(), facts.filterIsInstance<ItemFact.Offline>())
    }

    @Test
    fun `with no queued item the playing state still gives what it has`() {
        val facts = itemFacts(
            null,
            state.copy(publishedText = "3 years ago", viewsText = "9 views"),
            DownloadState.NotDownloaded,
            null
        )

        assertEquals(ItemFact.Published(null, "3 years ago"), facts.first())
        assertEquals(ItemFact.Views("9 views"), facts.single { it is ItemFact.Views })
        assertEquals(ItemFact.By("Channel 4"), facts.single { it is ItemFact.By })
    }

    @Test
    fun `when neither the row nor the player knows the date, the watch page's is used`() {
        val page = java.time.Instant.parse("2026-10-15T00:00:00Z")
        val facts =
            itemFacts(
                null,
                state.copy(publishedAt = null, publishedText = null),
                DownloadState.NotDownloaded,
                null,
                page
            )

        assertEquals(ItemFact.Published(page, null), facts.first())
    }
}
