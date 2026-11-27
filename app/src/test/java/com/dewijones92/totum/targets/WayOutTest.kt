package com.dewijones92.totum.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.targets.PlayOnOption
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPlays
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.PlaybackProblem
import com.dewijones92.totum.playback.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WayOutTest {

    private fun item(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("s"),
            id,
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://pi.example/$id.mkv"),
        ),
        PlayHandle.Podcast(),
    )

    private val phone = PlayOnOption(Target("phone", "This phone", TargetKind.PHONE))
    private val lounge = PlayOnOption(Target("lounge", "Lounge TV", TargetKind.TV))
    private val asleep = PlayOnOption(Target("bed", "Bedroom", TargetKind.TV), power = TargetPower.ASLEEP)
    private val jellyfinOnly =
        PlayOnOption(Target("jf", "Fire Stick Jellyfin", TargetKind.TV), plays = TargetPlays.JELLYFIN_ONLY)

    private fun way(
        problem: PlaybackProblem,
        item: PlayableItem,
        options: List<PlayOnOption>,
        reach: Int? = null,
        heldAtHome: Boolean = false,
        downloaded: Boolean = false,
    ) = wayOutOf(problem, item, WayOutFacts(heldAtHome, downloaded, options, reach))

    private val stopped = PlaybackProblem.CannotDecode(
        TrackKind.AUDIO,
        "E-AC-3",
        "ERROR_CODE_DECODING_FAILED",
        formatSupported = true,
        mimeType = "audio/eac3",
    )
    private val tooBig = PlaybackProblem.CannotDecode(
        TrackKind.VIDEO,
        "VP9",
        "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
        mimeType = "video/x-vnd.on2.vp9",
        width = 3840,
        height = 2160,
    )

    @Test
    fun `a torrent the phone's decoder stopped on is offered on the first Pi device that is awake`() {
        val way = way(stopped, item("torrent:abc"), listOf(phone, asleep, lounge))

        assertEquals(WayOut.PlayOn(lounge), way)
    }

    @Test
    fun `a file the Pi holds is offered even when its id is not a torrent's`() {
        val way = way(stopped, item("home-file"), listOf(phone, lounge), heldAtHome = true)

        assertEquals(WayOut.PlayOn(lounge), way)
    }

    @Test
    fun `a device that plays only Jellyfin is not offered a torrent, and a sleeping one is the last resort`() {
        val torrent = item("torrent:abc")

        assertNull(way(stopped, torrent, listOf(phone, jellyfinOnly)))
        assertEquals(WayOut.PlayOn(asleep), way(stopped, torrent, listOf(phone, asleep)))
    }

    @Test
    fun `an item that lives on the phone alone gets no Pi offer`() {
        assertNull(way(stopped, item("plain"), listOf(phone, lounge)))
    }

    @Test
    fun `a downloaded video beyond what the device reaches is offered a copy at that height`() {
        val way = way(tooBig, item("vid"), listOf(phone), reach = 1080, downloaded = true)

        assertEquals(WayOut.FetchCopy(1080), way)
    }

    @Test
    fun `no copy is offered when it is not downloaded, not too big or the reach is unknown`() {
        assertNull(way(tooBig, item("vid"), listOf(phone), reach = 1080))
        assertNull(way(tooBig, item("vid"), listOf(phone), reach = 2160, downloaded = true))
        assertNull(way(tooBig, item("vid"), listOf(phone), downloaded = true))
        assertNull(way(PlaybackProblem.Failed("x"), item("vid"), listOf(phone), reach = 1080, downloaded = true))
    }
}
