package com.dewijones92.totum.data.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
import com.dewijones92.totum.domain.MediaContentKind
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.playback.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetRulesTest {

    private fun video(id: String) = PlayableItem(
        MediaItem(MediaItemId(id), SourceId("c"), "Bluey $id", publishedAt = null, duration = null),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private fun podcast(id: String) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("feed"),
            "Episode $id",
            publishedAt = null,
            duration = null,
            mediaUrl = HttpUrl.of("https://example.com/$id.mp3"),
        ),
        PlayHandle.Podcast(),
    )

    private val owner = Requester("dewi-phone", "Dewi's phone")
    private val wife = Requester("wife-phone", "Wife's phone")

    @Test
    fun `only an item queued from the owner's phone reports to YouTube`() {
        val mine = QueueEntry(video("aaaaaaaaaaa")).toTargetEntry(owner, owner.id)
        val hers = QueueEntry(video("bbbbbbbbbbb")).toTargetEntry(wife, owner.id)
        val anonymous = QueueEntry(video("ccccccccccc")).toTargetEntry()

        assertEquals("aaaaaaaaaaa", TargetReporting.videoIdToReport(mine, signedIn = true))
        assertNull(TargetReporting.videoIdToReport(hers, signedIn = true))
        assertNull(TargetReporting.videoIdToReport(anonymous, signedIn = true))
    }

    @Test
    fun `nothing is reported while the Pi is signed out, and a podcast is never reported`() {
        val mine = QueueEntry(video("aaaaaaaaaaa")).toTargetEntry(owner, owner.id)
        val episode = QueueEntry(podcast("p1")).toTargetEntry(owner, owner.id)

        assertNull(TargetReporting.videoIdToReport(mine, signedIn = false))
        assertNull(TargetReporting.videoIdToReport(episode, signedIn = true))
        assertEquals("the Pi is not signed in to YouTube", TargetReporting.why(mine, signedIn = false))
        assertEquals("not a YouTube video", TargetReporting.why(episode, signedIn = true))
        val hers = QueueEntry(video("bbbbbbbbbbb")).toTargetEntry(wife, owner.id)
        assertTrue(TargetReporting.why(hers, true).contains("Wife"))
    }

    @Test
    fun `who added an item travels with it`() {
        val entry = QueueEntry(video("aaaaaaaaaaa")).toTargetEntry(wife, owner.id)

        assertEquals("Wife's phone", entry.addedBy)
        assertFalse(entry.reportsToYouTube)
    }

    @Test
    fun `a new target gets a colour of its own, never the phone's, and keeps it`() {
        val bar = Target("bar", "Soundbar")
        val tv = Target("tv", "LG TV", TargetKind.TV)

        val first = TargetColours.assign(listOf(bar, tv), emptyMap())

        assertEquals(TargetColours.PHONE, first.getValue(TargetColours.PHONE_ID))
        assertNotEquals(first.getValue("bar"), first.getValue("tv"))
        assertFalse(first.getValue("bar") == TargetColours.PHONE)
        assertEquals(first, TargetColours.assign(listOf(tv, bar), first))
        val third = TargetColours.assign(listOf(bar, tv, Target("mic", "Soundmic")), first)
        assertEquals(first.getValue("bar"), third.getValue("bar"))
        assertEquals(first.getValue("tv"), third.getValue("tv"))
        assertEquals(3, third.filterKeys { it != TargetColours.PHONE_ID }.values.toSet().size)
    }

    @Test
    fun `an edited colour is kept and not given to anyone else`() {
        val bar = Target("bar", "Soundbar")
        val edited = mapOf("bar" to TargetColours.palette[3])

        val colours = TargetColours.assign(listOf(bar, Target("tv", "LG", TargetKind.TV)), edited)

        assertEquals(TargetColours.palette[3], colours.getValue("bar"))
        assertNotEquals(TargetColours.palette[3], colours.getValue("tv"))
    }

    @Test
    fun `sending to a TV moves the playing item and the rest of its group, not what came before`() {
        val bluey = QueueGroup("shuffle:bluey", "Bluey")
        val snapshot = QueueSnapshot(
            listOf(
                QueueEntry(podcast("solo")),
                QueueEntry(video("aaaaaaaaaaa"), bluey),
                QueueEntry(video("bbbbbbbbbbb"), bluey),
                QueueEntry(video("ccccccccccc"), bluey),
                QueueEntry(podcast("after")),
            ),
            currentIndex = 2,
        )

        val plan = SendTo.plan(snapshot, playingPositionMs = 42_000)!!

        assertEquals(listOf("bbbbbbbbbbb", "ccccccccccc"), plan.items.map { it.itemId })
        assertEquals("bbbbbbbbbbb", plan.startItemId)
        assertEquals(42_000L, plan.startAtMs)
        assertEquals(listOf(2, 3), plan.toRemove)
        assertEquals("Bluey", plan.groupTitle)
    }

    @Test
    fun `a send is an insert after what plays there, never a replace of the target's queue`() {
        val bluey = QueueGroup("shuffle:bluey", "Bluey")
        val snapshot = QueueSnapshot(
            listOf(QueueEntry(video("aaaaaaaaaaa"), bluey), QueueEntry(video("bbbbbbbbbbb"), bluey)),
            currentIndex = 0,
        )
        val plan = SendTo.plan(snapshot, playingPositionMs = 9_000)!!

        val request = plan.request(owner)

        assertEquals(QueueMode.PLAY, request.mode)
        assertEquals(plan.items, request.items)
        assertEquals("aaaaaaaaaaa", request.startItemId)
        assertEquals(9_000L, request.startAtMs)
        assertEquals("Bluey", request.groupTitle)
        assertEquals(owner, request.from)
    }

    @Test
    fun `an ungrouped item is sent alone, and nothing playing sends nothing`() {
        val snapshot = QueueSnapshot(listOf(QueueEntry(podcast("a")), QueueEntry(podcast("b"))), currentIndex = 0)

        assertEquals(listOf("a"), SendTo.plan(snapshot, 0)!!.items.map { it.itemId })
        assertNull(SendTo.plan(QueueSnapshot(snapshot.entries), 0))
    }

    @Test
    fun `sound settings say whether they change anything`() {
        assertFalse(SoundSettings().changesSound)
        assertTrue(SoundSettings(skipSilence = true).changesSound)
        assertTrue(SoundSettings(boost = VolumeBoost.AUTO).changesSound)
    }

    @Test
    fun `a request and a state survive the wire`() {
        val request = TargetQueueRequest(
            listOf(video("aaaaaaaaaaa").toBackup()),
            QueueMode.REPLACE,
            from = owner,
            startAtMs = 5_000,
            startItemId = "aaaaaaaaaaa",
        )
        val text = TargetsJson.encodeToString(TargetQueueRequest.serializer(), request)
        assertEquals(request, TargetsJson.decodeFromString(TargetQueueRequest.serializer(), text))

        val state = TargetState(Target("tv", "LG", TargetKind.TV), sound = SoundSettings(true, VolumeBoost.AUTO))
        val back = TargetsJson.decodeFromString(
            TargetState.serializer(),
            TargetsJson.encodeToString(TargetState.serializer(), state),
        )
        assertEquals(state, back)
    }

    @Test
    fun `a target shows a picture only when its kind has one and what plays is not plain sound`() {
        val tv = Target("tv", "LG TV", TargetKind.TV)
        val speaker = Target("bar", "Soundbar")
        fun state(target: Target, item: PlayableItem?) = TargetState(
            target,
            entries = listOfNotNull(item?.let { QueueEntry(it).toTargetEntry() }),
            at = if (item == null) -1 else 0,
        )

        assertTrue(state(tv, video("aaaaaaaaaaa")).showsPicture)
        assertFalse(state(tv, podcast("p1")).showsPicture)
        assertFalse(state(speaker, video("aaaaaaaaaaa")).showsPicture)
        assertFalse(state(tv, null).showsPicture)
    }

    private fun live(id: String, kind: MediaContentKind = MediaContentKind.LIVE) = PlayableItem(
        MediaItem(
            MediaItemId(id),
            SourceId("c"),
            "Radio $id",
            publishedAt = null,
            duration = null,
            contentKind = kind,
        ),
        PlayHandle.Video(HttpUrl.of("https://www.youtube.com/watch?v=$id")),
    )

    private fun playing(item: PlayableItem, durationMs: Long?, live: Boolean = false) = TargetState(
        Target("tv", "LG TV", TargetKind.TV),
        entries = listOf(QueueEntry(item).toTargetEntry()),
        at = 0,
        durationMs = durationMs,
        live = live,
    )

    @Test
    fun `a live item has no length to show, whatever duration a player reported`() {
        val radio = playing(live("aaaaaaaaaaa"), durationMs = 43_199_000)

        assertTrue(radio.isLive)
        assertNull(radio.shownLengthMs)
    }

    @Test
    fun `a target that says it is live shows no length even for an item not marked live`() {
        val radio = playing(live("aaaaaaaaaaa", MediaContentKind.STANDARD), durationMs = 43_199_000, live = true)

        assertTrue(radio.isLive)
        assertNull(radio.shownLengthMs)
    }

    @Test
    fun `an item that is not live shows its length, and none when it is unknown`() {
        val talk = playing(live("aaaaaaaaaaa", MediaContentKind.STANDARD), durationMs = 90_000)

        assertFalse(talk.isLive)
        assertEquals(90_000L, talk.shownLengthMs)
        assertNull(talk.copy(durationMs = null).shownLengthMs)
        assertNull(talk.copy(durationMs = 0).shownLengthMs)
    }

    @Test
    fun `a state with no live field from an older Pi reads as not live`() {
        val old = """{"target":{"id":"tv","label":"LG TV","kind":"TV"}}"""

        assertFalse(TargetsJson.decodeFromString(TargetState.serializer(), old).live)
    }

    @Test
    fun `a state from an older Pi with no power reads as on, and a sleeping one survives the wire`() {
        val old = """{"target":{"id":"tv","label":"LG TV","kind":"TV"}}"""
        assertEquals(TargetPower.ON, TargetsJson.decodeFromString(TargetState.serializer(), old).power)

        val asleep = TargetState(Target("tv", "LG TV", TargetKind.TV), power = TargetPower.ASLEEP)
        val back = TargetsJson.decodeFromString(
            TargetState.serializer(),
            TargetsJson.encodeToString(TargetState.serializer(), asleep),
        )
        assertEquals(TargetPower.ASLEEP, back.power)
    }

    @Test
    fun `the wake control survives the wire`() {
        val text = TargetsJson.encodeToString(TargetControl.serializer(), TargetControl(TargetAction.WAKE))

        assertEquals(TargetAction.WAKE, TargetsJson.decodeFromString(TargetControl.serializer(), text).action)
    }
}
