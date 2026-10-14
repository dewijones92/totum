package com.dewijones92.totum.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchTargetTest {

    private fun item(id: String, music: Boolean) = MediaItem(
        id = MediaItemId(id),
        sourceId = SourceId("s"),
        title = id,
        publishedAt = null,
        duration = null,
        contentKind = if (music) MediaContentKind.MUSIC else MediaContentKind.STANDARD,
    )

    private val queue = listOf(
        item("talk1", music = false),
        item("song1", music = true),
        item("talk2", music = false),
        item("song2", music = true),
        item("talk3", music = false),
    )

    private fun jumpIndex(target: SwitchTarget) = (target as SwitchTarget.JumpTo).index

    @Test
    fun `from talk it goes to the music left last`() {
        assertEquals(
            3,
            jumpIndex(switchTarget(queue, current = 2, lastOf = mapOf(Listening.MUSIC to MediaItemId("song2"))))
        )
    }

    @Test
    fun `from music it goes back to the talk left last`() {
        assertEquals(
            0,
            jumpIndex(switchTarget(queue, current = 3, lastOf = mapOf(Listening.TALK to MediaItemId("talk1"))))
        )
    }

    @Test
    fun `with nothing remembered it takes the next of the other kind`() {
        assertEquals(1, jumpIndex(switchTarget(queue, current = 0, lastOf = emptyMap())))
    }

    @Test
    fun `with nothing ahead it takes the nearest of the other kind above`() {
        assertEquals(3, jumpIndex(switchTarget(queue, current = 4, lastOf = emptyMap())))
    }

    @Test
    fun `a remembered item no longer queued is ignored`() {
        assertEquals(
            1,
            jumpIndex(switchTarget(queue, current = 0, lastOf = mapOf(Listening.MUSIC to MediaItemId("gone"))))
        )
    }

    @Test
    fun `no music queued asks for a recent music action`() {
        val talkOnly = listOf(item("talk1", music = false), item("talk2", music = false))

        assertTrue(switchTarget(talkOnly, current = 0, lastOf = emptyMap()) is SwitchTarget.PlayRecentMusic)
    }

    @Test
    fun `no talk queued goes nowhere`() {
        val musicOnly = listOf(item("song1", music = true), item("song2", music = true))

        assertTrue(switchTarget(musicOnly, current = 0, lastOf = emptyMap()) is SwitchTarget.Nowhere)
    }

    @Test
    fun `nothing playing goes nowhere`() {
        assertTrue(switchTarget(queue, current = -1, lastOf = emptyMap()) is SwitchTarget.Nowhere)
    }
}
