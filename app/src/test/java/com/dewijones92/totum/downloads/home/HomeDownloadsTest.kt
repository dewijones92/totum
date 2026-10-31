package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.backup.toBackup
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.home.HomeJob
import com.dewijones92.totum.data.home.HomeJobState
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.domain.HomeCopy
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.PlayableItem
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    }

    private val api = FakeHomeApi()
    private val notices = Notices()
    private val local = FakeDownloadManager()
    private var unmetered = false
    private val tokens = OAuthTokens(AccessToken("a"), RefreshToken("r"), 9)

    private fun home(scope: kotlinx.coroutines.CoroutineScope) = HomeDownloads(
        api,
        PhoneSide({ tokens }, { listOf(PhoneDecoder("avc", 1080, true)) }, { unmetered }),
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
        api.refuse = "❌ Not enough space at home (12 GB free)"
        home(this).start(video, null)
        advanceUntilIdle()
        assertEquals(listOf("refused A film: ❌ Not enough space at home (12 GB free)"), notices.said)
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
}
