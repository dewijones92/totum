package com.dewijones92.totum.data.targets

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.queue.QueueEntry
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.data.queue.QueueSnapshot
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

    private val owner = Requester("dewi-phone", "Dewi's phone", owner = true)
    private val wife = Requester("wife-phone", "Wife's phone", owner = false)

    @Test
    fun `only an item queued from the owner's phone reports to YouTube`() {
        val mine = QueueEntry(video("aaaaaaaaaaa")).toTargetEntry(owner)
        val hers = QueueEntry(video("bbbbbbbbbbb")).toTargetEntry(wife)
        val anonymous = QueueEntry(video("ccccccccccc")).toTargetEntry()

        assertEquals("aaaaaaaaaaa", TargetReporting.videoIdToReport(mine, signedIn = true))
        assertNull(TargetReporting.videoIdToReport(hers, signedIn = true))
        assertNull(TargetReporting.videoIdToReport(anonymous, signedIn = true))
    }

    @Test
    fun `nothing is reported while the Pi is signed out, and a podcast is never reported`() {
        val mine = QueueEntry(video("aaaaaaaaaaa")).toTargetEntry(owner)
        val episode = QueueEntry(podcast("p1")).toTargetEntry(owner)

        assertNull(TargetReporting.videoIdToReport(mine, signedIn = false))
        assertNull(TargetReporting.videoIdToReport(episode, signedIn = true))
        assertEquals("the Pi is not signed in to YouTube", TargetReporting.why(mine, signedIn = false))
        assertEquals("not a YouTube video", TargetReporting.why(episode, signedIn = true))
        assertTrue(TargetReporting.why(QueueEntry(video("bbbbbbbbbbb")).toTargetEntry(wife), true).contains("Wife"))
    }

    @Test
    fun `who added an item travels with it`() {
        val entry = QueueEntry(video("aaaaaaaaaaa")).toTargetEntry(wife)

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
}
