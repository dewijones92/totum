package com.dewijones92.totum.queue

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayTrailTest {
    private fun item(id: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("s"), id, publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )
    private val song = item("song")
    private val podcast = item("podcast")
    private val film = item("film")

    @Test
    fun `back and forward walk what was played, like a browser`() {
        val trail = PlayTrail()
        listOf(song, podcast, film).forEach(trail::noted)
        assertEquals(podcast, trail.back())
        trail.noted(podcast)
        assertEquals(song, trail.back())
        trail.noted(song)
        assertNull(trail.back())
        assertEquals(podcast, trail.forward())
        trail.noted(podcast)
        assertEquals(PlayTrail.Place(song, film), trail.around.value)
    }

    @Test
    fun `playing something new after going back drops what was ahead`() {
        val trail = PlayTrail()
        listOf(song, podcast, film).forEach(trail::noted)
        trail.back()
        trail.noted(podcast)
        val other = item("other")
        trail.noted(other)
        assertEquals(PlayTrail.Place(podcast, null), trail.around.value)
        assertNull(trail.forward())
    }

    @Test
    fun `an item played again moves to the end instead of appearing twice`() {
        val trail = PlayTrail()
        listOf(song, podcast, song).forEach(trail::noted)
        assertEquals(podcast, trail.back())
        trail.noted(podcast)
        assertNull(trail.back())
    }

    @Test
    fun `the trail is kept across restarts and capped`() {
        val saved = object : TrailStore {
            var items: List<PlayableItem> = emptyList()
            var cursor = -1
            override fun load() = items to cursor
            override fun save(items: List<PlayableItem>, cursor: Int) {
                this.items = items
                this.cursor = cursor
            }
        }
        val trail = PlayTrail(saved, limit = 3)
        (1..5).map { item("i$it") }.forEach(trail::noted)
        assertEquals(listOf("i3", "i4", "i5"), saved.items.map { it.item.id.value })
        assertEquals(item("i4"), PlayTrail(saved, limit = 3).back())
    }

    @Test
    fun `going to a place keeps everything ahead and appends nothing`() {
        val trail = PlayTrail()
        listOf(song, podcast, film).forEach(trail::noted)

        assertEquals(song, trail.goTo(0))
        trail.noted(song)

        assertEquals(listOf(song, podcast, film), trail.snapshot().first)
        assertEquals(0, trail.snapshot().second)
        assertEquals(PlayTrail.Place(null, podcast), trail.around.value)
        assertEquals(PlayTrail.Listing(listOf(song, podcast, film), 0), trail.listing.value)
        assertEquals(podcast, trail.forward())
    }

    @Test
    fun `going to the place you are at, or one that is not there, goes nowhere`() {
        val trail = PlayTrail()
        listOf(song, podcast).forEach(trail::noted)

        assertNull(trail.goTo(1))
        assertNull(trail.goTo(7))
        assertNull(trail.goTo(-1))
    }
}
