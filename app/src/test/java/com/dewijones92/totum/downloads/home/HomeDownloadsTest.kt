package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.HomeProblem
import com.dewijones92.totum.data.home.HomeSeverity
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.DownloadedMedia
import com.dewijones92.totum.domain.HomeCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.domain.StorageUsage
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeDownloadsTest {

    private val watch = HttpUrl.of("https://www.youtube.com/watch?v=abcdefghijk")
    private val video = PlayableItem(
        MediaItem(MediaItemId("abcdefghijk"), SourceId("s"), "A film", publishedAt = null, duration = null),
        PlayHandle.Video(watch),
    )

    private class Notices : HomeNotices {
        val said = mutableListOf<String>()
        override fun started(title: String) { said += "started $title" }
        override fun ready(title: String) { said += "ready $title" }
        override fun refused(title: String, why: String) { said += "refused $title: $why" }
        override fun problem(key: String, text: String) { said += "problem $text" }
    }

    private val api = FakeHomeApi()
    private val notices = Notices()
    private val local = FakeDownloadManager()
    private var unmetered = false
    private val tokens = OAuthTokens(AccessToken("a"), RefreshToken("r"), 9)

    private fun home(scope: kotlinx.coroutines.CoroutineScope) = HomeDownloads(
        api,
        PhoneSide({ tokens }, { listOf(PhoneDecoder("avc", 1080, true)) }, { unmetered }, copyOnWifi = { true }),
        local,
        Operations(),
        notices,
        scope,
        Pacing(pollMs = 1),
    )

    private fun job(state: HomeJobState, audioOnly: Boolean = false) =
        HomeJob("abcdefghijk", video.toBackup(), state, audioOnly, bytes = 5, totalBytes = 10)

    @Test
    fun `a video sent home hands over the sign-in once, with the phone's decoders and the chosen height`() = runTest {
        val home = home(this)
        home.start(video, DownloadChoice.Video(720))
        home.start(video, DownloadChoice.Sound)
        advanceUntilIdle()
        assertEquals(1, api.handedOver.size)
        assertEquals("r", api.handedOver.single().refreshToken)
        assertEquals(720, api.started[0].maxHeight)
        assertEquals(listOf(PhoneDecoder("avc", 1080, true)), api.started[0].decoders)
        assertTrue(api.started[1].audioOnly)
        assertEquals(listOf("started A film", "started A film"), notices.said)
    }

    @Test
    fun `a refusal is told, not swallowed`() = runTest {
        api.refuse = "❌ Not enough space on the Pi (12 GB free)"
        home(this).start(video, null)
        advanceUntilIdle()
        assertEquals(listOf("refused A film: ❌ Not enough space on the Pi (12 GB free)"), notices.said)
    }

    @Test
    fun `a finished copy is offered to play, and copied only on an unmetered network`() = runTest {
        val home = home(this)
        api.listed = listOf(job(HomeJobState.DOWNLOADING))
        home.refresh("test")
        assertNull(home.atHome(video))
        api.listed = listOf(job(HomeJobState.DONE))
        home.refresh("test")
        assertEquals(HomeCopy(HttpUrl.of("https://home.test/fetch/files/abcdefghijk"), false), home.atHome(video))
        assertEquals(listOf("ready A film"), notices.said)
        assertTrue("nothing copied on mobile data", local.requested.isEmpty())
        unmetered = true
        home.refresh("on Wi-Fi")
        assertEquals(listOf(MediaItemId("abcdefghijk") to false), local.requested)
    }

    @Test
    fun `a job that was already done when the app started is not announced`() = runTest {
        api.listed = listOf(job(HomeJobState.DONE))
        home(this).refresh("launch")
        assertTrue(notices.said.isEmpty())
    }

    @Test
    fun `a video at home replaces a sound-only copy on the phone, never the other way round`() = runTest {
        unmetered = true
        local.place(com.dewijones92.totum.domain.DownloadedMedia(video, "/phone/a.m4a", audioOnly = true))
        api.listed = listOf(job(HomeJobState.DONE, audioOnly = false))
        home(this).refresh("video ready")
        assertEquals(listOf(MediaItemId("abcdefghijk") to false), local.requested)

        local.requested.clear()
        local.place(com.dewijones92.totum.domain.DownloadedMedia(video, "/phone/a.mkv", audioOnly = false))
        api.listed = listOf(job(HomeJobState.DONE, audioOnly = true))
        home(this).refresh("sound ready")
        assertTrue(local.requested.isEmpty())
    }

    @Test
    fun `after sending a job home it is followed closely, not at the idle pace`() = runTest {
        val home = HomeDownloads(
            api,
            PhoneSide({ tokens }, { emptyList() }, { false }),
            local,
            Operations(),
            notices,
            backgroundScope,
            Pacing(pollMs = 1_000, idleMs = 600_000),
        )
        home.watch()
        advanceTimeBy(5_000)
        api.listed = listOf(job(HomeJobState.DOWNLOADING))
        home.start(video, null)
        advanceTimeBy(500)
        api.listed = listOf(job(HomeJobState.DONE))
        advanceTimeBy(3_000)
        assertTrue(notices.said.toString(), "ready A film" in notices.said)
    }

    @Test
    fun `cancelling a home download from the strip cancels it at home`() = runTest {
        val operations = Operations()
        val home = HomeDownloads(
            api,
            PhoneSide({ tokens }, { emptyList() }, { false }),
            local,
            operations,
            notices,
            backgroundScope,
            Pacing(pollMs = 1_000, idleMs = 600_000)
        )
        api.listed = listOf(job(HomeJobState.DOWNLOADING))
        home.refresh("running")
        advanceTimeBy(100)
        operations.cancel(operations.running.value.single().id)
        advanceTimeBy(100)
        assertEquals(listOf("abcdefghijk"), api.cancelled)
    }

    @Test
    fun `a phone copy moved home is deleted from the phone once home has it, a copy stays on both`() = runTest {
        val kept = InMemoryKeptAtHome()
        val moving = InMemoryKeptAtHome()
        val home = HomeDownloads(
            api,
            PhoneSide({ tokens }, { emptyList() }, { false }, { true }, kept, moving),
            local,
            Operations(),
            notices,
            this,
            Pacing(pollMs = 1),
        )
        local.place(DownloadedMedia(video, "/phone/a.mkv", audioOnly = true))
        home.sendFromPhone(video.item.id, move = true)
        advanceUntilIdle()
        assertTrue("the phone copy's variant is what goes home", api.started.single().audioOnly)
        api.listed = listOf(job(HomeJobState.DOWNLOADING, audioOnly = true))
        home.refresh("busy")
        assertEquals("still on the phone while home fetches", 1, local.observeDownloaded().first().size)
        api.listed = listOf(job(HomeJobState.DONE, audioOnly = true))
        home.refresh("done")
        advanceUntilIdle()
        assertTrue("deleted from the phone", local.observeDownloaded().first().isEmpty())
        assertTrue("kept at home, not copied back", "abcdefghijk" in kept.ids.value)
        assertTrue(moving.ids.value.isEmpty())

        local.place(DownloadedMedia(video, "/phone/a.mkv", audioOnly = false))
        api.started.clear()
        home.sendFromPhone(video.item.id, move = false)
        advanceUntilIdle()
        home.refresh("done again")
        advanceUntilIdle()
        assertEquals(false, api.started.single().audioOnly)
        assertEquals("a copy stays on the phone", 1, local.observeDownloaded().first().size)
    }

    @Test
    fun `a move cancelled at home is forgotten, so a later copy never deletes the phone copy`() = runTest {
        val moving = InMemoryKeptAtHome()
        val home = HomeDownloads(
            api,
            PhoneSide({ tokens }, { emptyList() }, { false }, { true }, InMemoryKeptAtHome(), moving),
            local,
            Operations(),
            notices,
            this,
            Pacing(pollMs = 1),
        )
        local.place(DownloadedMedia(video, "/phone/a.mkv", audioOnly = false))
        home.sendFromPhone(video.item.id, move = true)
        advanceUntilIdle()
        api.listed = listOf(job(HomeJobState.WAITING))
        home.refresh("waiting")
        api.listed = emptyList()
        home.refresh("cancelled at home")
        home.sendFromPhone(video.item.id, move = false)
        advanceUntilIdle()
        api.listed = listOf(job(HomeJobState.DONE))
        home.refresh("done")
        advanceUntilIdle()
        assertEquals("the copy stays on the phone", 1, local.observeDownloaded().first().size)
        assertTrue(moving.ids.value.isEmpty())
    }

    @Test
    fun `kept at home is not copied back, copy now copies anywhere, the setting stops copying`() = runTest {
        val kept = InMemoryKeptAtHome()
        var copyOnWifi = true
        unmetered = true
        val home = HomeDownloads(
            api,
            PhoneSide({ tokens }, { emptyList() }, { unmetered }, { copyOnWifi }, kept),
            local,
            Operations(),
            notices,
            this,
            Pacing(pollMs = 1),
        )
        api.listed = listOf(job(HomeJobState.DONE))
        home.deleteOnPhoneOnly(MediaItemId("abcdefghijk"))
        advanceUntilIdle()
        home.refresh("wifi")
        assertTrue("kept at home is not copied back", local.requested.isEmpty())

        unmetered = false
        home.copyNow(MediaItemId("abcdefghijk"))
        advanceUntilIdle()
        assertEquals(listOf(MediaItemId("abcdefghijk") to false), local.requested)
        assertTrue(kept.ids.value.isEmpty())

        local.requested.clear()
        unmetered = true
        copyOnWifi = false
        local.setFailed(MediaItemId("abcdefghijk"), "gone")
        home.refresh("setting off")
        assertTrue(local.requested.isEmpty())
    }

    @Test
    fun `the phone knows how much space is used and free at home`() = runTest {
        api.freeBytes = 312_000_000_000
        api.listed = listOf(
            job(HomeJobState.DONE).copy(bytes = 2_000, totalBytes = 2_000),
            job(HomeJobState.DOWNLOADING).copy(id = "another"),
        )
        val home = home(backgroundScope)
        home.refresh("test")
        assertEquals(StorageUsage(itemCount = 1, usedBytes = 2_000, freeBytes = 312_000_000_000), home.storage.value)
    }

    @Test
    fun `a new problem at home is told once and one already there at start is shown but not announced`() = runTest {
        val disk = HomeProblem("disk", HomeSeverity.WARNING, "⚠️ Only 12 GB free on the Pi")
        val ai = HomeProblem("ai", HomeSeverity.BROKEN, "❌ The AI service on the Pi is not answering")
        api.problems = listOf(disk)
        val home = home(backgroundScope)
        home.refresh("start")
        api.problems = listOf(disk, ai)
        home.refresh("later")
        home.refresh("again")
        assertEquals(listOf(disk, ai), home.problems.value)
        assertEquals(listOf("problem ❌ The AI service on the Pi is not answering"), notices.said)
    }
}
