package com.dewijones92.totum.downloads.home

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.download.fake.FakeDownloadManager
import com.dewijones92.totum.data.home.PhoneDecoder
import com.dewijones92.totum.data.torrent.TorrentFile
import com.dewijones92.totum.data.torrent.TorrentLink
import com.dewijones92.totum.data.torrent.fake.FakeHomeTorrentServer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.operations.Operations
import com.dewijones92.totum.settings.DownloadChoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SendHomeTest {

    private val api = FakeHomeApi()
    private val torrents = FakeHomeTorrentServer()
    private var remembered: DownloadChoice? = null
    private val described = mutableListOf<HttpUrl>()

    private fun send(scope: CoroutineScope, home: Boolean = true) = SendHome(
        HomeDownloads(
            api.takeIf { home },
            PhoneSide({
                OAuthTokens(AccessToken("a"), RefreshToken("r"), 9)
            }, { listOf(PhoneDecoder("avc", 1080, true)) }, { false }),
            FakeDownloadManager(),
            Operations(),
            NoNotices,
            scope,
            Pacing(pollMs = 1),
        ),
        { torrents },
        { url ->
            described += url
            MediaItem(
                MediaItemId("abcdefghijk"),
                SourceId("yt"),
                "Described title",
                publishedAt = null,
                duration = null
            )
        },
        { remembered },
    )

    private object NoNotices : HomeNotices {
        override fun started(title: String) = Unit
        override fun ready(title: String) = Unit
        override fun refused(title: String, why: String) = Unit
        override fun problem(key: String, text: String) = Unit
    }

    @Test
    fun `a shared YouTube link is downloaded at home with the remembered choice`() = runTest {
        remembered = DownloadChoice.Video(720)
        val sent = send(this).send("Watch this https://youtu.be/abcdefghijk?si=x")
        advanceUntilIdle()
        assertEquals(Sent.Video("Described title"), sent)
        assertEquals("abcdefghijk", api.started.single().item.itemId)
        assertEquals(720, api.started.single().maxHeight)
        assertEquals(1, described.size)
    }

    @Test
    fun `with nothing remembered a shared video is sent home as the best video`() = runTest {
        send(this).send("https://www.youtube.com/watch?v=abcdefghijk")
        advanceUntilIdle()
        assertEquals(false, api.started.single().audioOnly)
        assertEquals(null, api.started.single().maxHeight)
    }

    @Test
    fun `a shared magnet is prepared at home and each playable file is downloaded there`() = runTest {
        torrents.files = listOf(
            TorrentFile(index = 1, path = "Show/ep1.mkv", sizeBytes = 400_000_000),
            TorrentFile(index = 2, path = "Show/ep2.mkv", sizeBytes = 400_000_000),
            TorrentFile(index = 3, path = "Show/readme.txt", sizeBytes = 100),
        )
        val sent = send(this).send("magnet:?xt=urn:btih:abc123&dn=Show")
        advanceUntilIdle()
        assertEquals(Sent.Torrent("Some.Release", 2), sent)
        assertEquals(TorrentLink.Magnet("magnet:?xt=urn:btih:abc123&dn=Show"), torrents.prepared.single())
        assertEquals(2, api.started.size)
    }

    @Test
    fun `a shared film with featurettes sends only the film home`() = runTest {
        torrents.files = listOf(
            TorrentFile(index = 1, path = "Film/Film.2025.mkv", sizeBytes = 5_000_000_000),
            TorrentFile(index = 2, path = "Film/Featurettes/Making of.mkv", sizeBytes = 100_000_000),
        )
        val sent = send(this).send("magnet:?xt=urn:btih:abc123&dn=Film")
        advanceUntilIdle()
        assertEquals(Sent.Torrent("Some.Release", 1), sent)
        assertEquals(listOf("torrent:abc123:1"), api.started.map { it.item.itemId })
    }

    @Test
    fun `text with no link is refused and nothing is sent`() = runTest {
        val sent = send(this).send("just some words")
        advanceUntilIdle()
        assertEquals(Sent.Refused(NotSent.NO_LINK), sent)
        assertTrue(api.started.isEmpty())
    }

    @Test
    fun `with no home server nothing is sent and it says so`() = runTest {
        val sent = send(this, home = false).send("https://youtu.be/abcdefghijk")
        assertEquals(Sent.Refused(NotSent.NO_HOME), sent)
    }
}
