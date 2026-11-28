package com.dewijones92.totum.ui.common

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FacedFromRowTest {

    private fun state(title: String, art: String?, id: String = "AHqM1WhyBus", shielded: Boolean = false) =
        PlaybackState(
            itemId = MediaItemId(id),
            title = title,
            artist = null,
            artworkUrl = art,
            isPlaying = true,
            positionMs = 0,
            durationMs = null,
            speed = 1f,
            shielded = shielded,
        )

    private fun row(
        id: String = "AHqM1WhyBus",
        title: String = "Schubert: Piano Trio No. 2",
        art: String? = "https://i.ytimg.com/a.jpg",
    ) =
        MediaItem(
            MediaItemId(id),
            SourceId("music:radio:x"),
            title,
            publishedAt = null,
            duration = null,
            thumbnailUrl = art?.let(HttpUrl::of),
        )

    @Test
    fun `a session item that came with no title and no picture shows the queue row's`() {
        val faced = state(title = "", art = null).facedFrom(row())

        assertEquals("Schubert: Piano Trio No. 2", faced.title)
        assertEquals("https://i.ytimg.com/a.jpg", faced.artworkUrl)
    }

    @Test
    fun `only what is missing is taken, and a blank title does not hide a picture the session has`() {
        val faced = state(title = "", art = "https://i.ytimg.com/session.jpg").facedFrom(row())

        assertEquals("Schubert: Piano Trio No. 2", faced.title)
        assertEquals("https://i.ytimg.com/session.jpg", faced.artworkUrl)
        assertEquals("Own title", state("Own title", art = null).facedFrom(row()).title)
    }

    @Test
    fun `a session that has both is returned untouched`() {
        val whole = state("Own title", "https://i.ytimg.com/own.jpg")

        assertSame(whole, whole.facedFrom(row()))
    }

    @Test
    fun `another item's row is never borrowed from`() {
        val blank = state(title = "", art = null)

        assertSame(blank, blank.facedFrom(row(id = "somethingElse")))
        assertSame(blank, blank.facedFrom(null))
    }

    @Test
    fun `a shielded title is never replaced by the row's real one`() {
        val hidden = state(title = "", art = null, shielded = true)

        assertEquals("", hidden.facedFrom(row()).title)
    }
}
