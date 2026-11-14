package com.dewijones92.totum.data.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayOnTest {

    private val lg = TargetState(Target("lg-tv", "LG TV", TargetKind.TV), power = TargetPower.ASLEEP)
    private val bar = TargetState(Target("soundbar", "LG Soundbar", TargetKind.SPEAKER))
    private val mic = TargetState(Target("soundmic", "Soundmic", TargetKind.SPEAKER))
    private val browser = TargetState(Target("pc", "Dewi's PC", TargetKind.BROWSER))

    private fun entry(id: String) = TargetEntry(
        PlayableItem(
            MediaItem(MediaItemId(id), SourceId("c"), "Bluey $id", publishedAt = null, duration = null),
            PlayHandle.Podcast(audioUrl = HttpUrl.of("https://example.com/$id.mp3")),
        ).toBackup(),
    )

    @Test
    fun `at home every listed device is offered, switched off or not, after this phone`() {
        val options = PlayOn.options(listOf(lg, bar, mic), atHome = true)

        assertEquals(listOf("phone", "lg-tv", "soundbar", "soundmic"), options.map { it.id })
        assertEquals(TargetPower.ASLEEP, options.first { it.id == "lg-tv" }.power)
    }

    @Test
    fun `away from home only this phone is offered, a browser included`() {
        val options = PlayOn.options(listOf(lg, bar, browser), atHome = false)

        assertEquals(listOf("phone"), options.map { it.id })
    }

    @Test
    fun `with nothing listed the only choice is this phone`() {
        val options = PlayOn.options(emptyList(), atHome = true)

        assertEquals(listOf("phone"), options.map { it.id })
        assertNull(PlayOn.destination("lg-tv", options))
    }

    @Test
    fun `the last used device is the default while it is offered`() {
        val options = PlayOn.options(listOf(lg, bar), atHome = true)

        assertEquals("lg-tv", PlayOn.resolve("lg-tv", options).id)
        assertEquals("lg-tv", PlayOn.destination("lg-tv", options))
    }

    @Test
    fun `a last used device that is not offered falls back to this phone, and the reason is worded`() {
        val away = PlayOn.options(listOf(lg), atHome = false)
        val missing = PlayOn.options(listOf(bar), atHome = true)

        assertNull(PlayOn.destination("lg-tv", away))
        assertNull(PlayOn.destination("lg-tv", missing))
        assertTrue(PlayOn.why("lg-tv", away, atHome = false).contains("not at home"))
        assertTrue(PlayOn.why("lg-tv", missing, atHome = true).contains("not listed"))
        assertEquals("last used LG TV", PlayOn.why("lg-tv", PlayOn.options(listOf(lg), true), true))
    }

    @Test
    fun `an option carries whether its device is switched on by hand`() {
        val byHand = mic.copy(power = TargetPower.ASLEEP, switchedOnByHand = true)

        val options = PlayOn.options(listOf(lg, byHand), atHome = true)

        assertTrue(options.first { it.id == "soundmic" }.switchedOnByHand)
        assertFalse(options.first { it.id == "lg-tv" }.switchedOnByHand)
    }

    @Test
    fun `a switched off device says how it comes on, by a pick or by hand, and a device that is on says nothing`() {
        val sleepingTv = PlayOnOption(lg.target, TargetPower.ASLEEP)
        val sleepingMic = PlayOnOption(mic.target, TargetPower.ASLEEP, switchedOnByHand = true)
        val liveMic = PlayOnOption(mic.target, TargetPower.ON, switchedOnByHand = true)

        assertEquals(PlayOnHint.WAKES_WHEN_PICKED, sleepingTv.hint)
        assertEquals(PlayOnHint.SWITCH_ON_BY_HAND, sleepingMic.hint)
        assertEquals(PlayOnHint.NONE, liveMic.hint)
        assertEquals(PlayOnHint.NONE, PlayOnOption(bar.target).hint)
        assertEquals(PlayOnHint.NONE, PlayOnOption(lg.target, TargetPower.WAKING).hint)
    }

    @Test
    fun `a device that is switched on by hand is never woken by a pick`() {
        assertFalse(PlayOnOption(mic.target, TargetPower.ASLEEP, switchedOnByHand = true).wakesWhenPicked)
        assertTrue(PlayOnOption(lg.target, TargetPower.ASLEEP).wakesWhenPicked)
        assertFalse(PlayOnOption(lg.target, TargetPower.ON).wakesWhenPicked)
    }

    @Test
    fun `no last used device means this phone`() {
        val options = PlayOn.options(listOf(lg), atHome = true)

        assertTrue(PlayOn.resolve(null, options).isPhone)
        assertNull(PlayOn.destination(null, options))
    }

    @Test
    fun `home means the home server answered and the connection is not metered`() {
        assertTrue(PlayOn.atHome(homeServerAnswered = true, onUnmeteredNetwork = true))
        assertFalse(PlayOn.atHome(homeServerAnswered = true, onUnmeteredNetwork = false))
        assertFalse(PlayOn.atHome(homeServerAnswered = false, onUnmeteredNetwork = true))
    }

    @Test
    fun `an item is marked with the device it is playing on, not one that only has it queued or is paused`() {
        val playing = bar.copy(entries = listOf(entry("a"), entry("b")), at = 1, playing = true)
        val paused = lg.copy(entries = listOf(entry("c")), at = 0, playing = false)
        val queuedOnly = mic.copy(entries = listOf(entry("b")), at = -1, playing = false)

        val marks = PlayOn.playingNow(listOf(playing, paused, queuedOnly))

        assertEquals(mapOf("b" to bar.target), marks)
    }
}
