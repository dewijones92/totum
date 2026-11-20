package com.dewijones92.totum.data.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TargetPlaysTest {

    private fun item(id: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("c"), "Title $id", publishedAt = null, duration = null),
        PlayHandle.Podcast(audioUrl = HttpUrl.of("https://example.com/x.mp4")),
    )

    @Test
    fun `a target that plays anything refuses nothing`() {
        assertNull(TargetPlays.ANYTHING.refusal(item("dQw4w9WgXcQ")))
        assertNull(TargetPlays.ANYTHING.refusal(item("jellyfin:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
    }

    @Test
    fun `a Jellyfin-only target takes Jellyfin items and refuses everything else with the reason`() {
        assertNull(TargetPlays.JELLYFIN_ONLY.refusal(item("jellyfin:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")))
        assertEquals(TargetPlays.ONLY_JELLYFIN, TargetPlays.JELLYFIN_ONLY.refusal(item("dQw4w9WgXcQ")))
        assertNotNull(TargetPlays.JELLYFIN_ONLY.refusal(item("urn:bbc:podcast:w3ct999n")))
        assertNotNull(TargetPlays.JELLYFIN_ONLY.refusal(item("jellyfin:not-an-id!")))
        assertNotNull(TargetPlays.JELLYFIN_ONLY.refusal(item("jellyfin:")))
    }

    @Test
    fun `a state from an older Pi, with no plays field, plays anything`() {
        val old = """{"target":{"id":"lg-tv","label":"LG TV","kind":"TV"}}"""

        val state = TargetsJson.decodeFromString(TargetState.serializer(), old)

        assertEquals(TargetPlays.ANYTHING, state.plays)
    }

    @Test
    fun `a state that plays only Jellyfin survives the wire, and a phone that does not know the field ignores it`() {
        val state = TargetState(Target("jf", "Jellyfin", TargetKind.TV), plays = TargetPlays.JELLYFIN_ONLY)

        val text = TargetsJson.encodeToString(TargetState.serializer(), state)

        assertEquals(TargetPlays.JELLYFIN_ONLY, TargetsJson.decodeFromString(TargetState.serializer(), text).plays)
        assertEquals(true, text.contains("\"plays\":\"JELLYFIN_ONLY\""))
    }

    @Test
    fun `the picker says what a Jellyfin-only target takes, and that it is closed when it is asleep`() {
        val open = TargetState(Target("jf", "Jellyfin", TargetKind.TV), plays = TargetPlays.JELLYFIN_ONLY)
        val closed = open.copy(power = TargetPower.ASLEEP, switchedOnByHand = true)

        val options = PlayOn.options(listOf(open, closed.copy(target = Target("jf2", "Closed", TargetKind.TV))), true)

        assertEquals(PlayOnHint.JELLYFIN_ONLY, options.first { it.id == "jf" }.hint)
        assertEquals(PlayOnHint.JELLYFIN_NOT_OPEN, options.first { it.id == "jf2" }.hint)
        assertEquals(PlayOnHint.NONE, PlayOnOption(Target("x", "X", TargetKind.TV)).hint)
    }
}
