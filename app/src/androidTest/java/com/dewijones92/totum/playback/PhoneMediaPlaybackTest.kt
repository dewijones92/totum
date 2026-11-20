package com.dewijones92.totum.playback

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.data.phone.PhoneMediaBrowser
import com.dewijones92.totum.data.phone.PhoneMediaQuery
import com.dewijones92.totum.data.queue.QueueGroup
import com.dewijones92.totum.domain.PhoneMediaKind
import com.dewijones92.totum.domain.PlayHandle
import com.dewijones92.totum.domain.isOnThisPhone
import com.dewijones92.totum.domain.toPlayableOrNull
import com.dewijones92.totum.settings.PlaybackMode
import com.dewijones92.totum.support.PhoneMediaFixtures
import com.dewijones92.totum.support.PlaybackWaits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class PhoneMediaPlaybackTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container get() = (context.applicationContext as TotumApplication).container
    private val controller get() = container.playbackController

    @Before
    fun setUp() = runBlocking(Dispatchers.Main) {
        PhoneMediaFixtures.grantMediaPermissions()
        withTimeout(TIMEOUT) { while (controller.player == null) delay(100) }
        container.appPreferences.setPlaybackMode(PlaybackMode.VIDEO)
        container.playbackQueue.clear()
        controller.setSkipSilence(false)
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.Main) {
        container.playbackQueue.clear()
        controller.player?.stop()
        controller.player?.clearMediaItems()
        container.appPreferences.setPlaybackMode(PlaybackMode.AUTO)
    }

    private suspend fun playFirstMatching(kind: PhoneMediaKind, name: String) = withContext(Dispatchers.Main) {
        val page = container.phoneMedia.device.page(PhoneMediaQuery(kind, search = name))
        val entry = page.entries.firstOrNull { it.title.contains(name) }
        assertNotNull("the media store should list $name; it listed ${page.entries.map { it.title }}", entry)
        val playable = checkNotNull(entry?.toPlayableOrNull())
        container.playbackQueue.playAll(
            listOf(playable),
            QueueGroup("phone-test", "📱 test"),
            startAt = playable.item.id
        )
        entry!! to playable
    }

    @Test
    fun aVideoOnThePhonePlaysThroughTheQueueFromItsOwnFile() = runBlocking(Dispatchers.Main) {
        val fixture = PhoneMediaFixtures.video(context, "totum-phone-video.mp4")
        val (entry, playable) = playFirstMatching(PhoneMediaKind.VIDEO, "totum-phone-video")

        val state = PlaybackWaits.awaitStateOf(controller, playable.item.id, TIMEOUT) { it.isPlaying && it.hasVideo }

        assertTrue("it should play with its picture: ${PlaybackWaits.whatIsActuallyPlaying(controller)}", state != null)
        assertEquals(fixture.path, entry.path)
        assertEquals(PlayHandle.LocalVideo(fixture.path), playable.handle)
        assertEquals(fixture.path, controller.player?.currentMediaItem?.localConfiguration?.uri?.path)
        assertTrue(playable.item.isOnThisPhone)
        assertEquals(playable.item.id, container.playbackQueue.state.value.current?.item?.item?.id)
    }

    @Test
    fun aSongOnThePhonePlaysAsSoundWithNoPicture() = runBlocking(Dispatchers.Main) {
        PhoneMediaFixtures.song(context, "totum-phone-tone.wav")
        val (_, playable) = playFirstMatching(PhoneMediaKind.AUDIO, "totum-phone-tone")

        val state = PlaybackWaits.awaitStateOf(controller, playable.item.id, TIMEOUT) { it.isPlaying }

        assertTrue("it should be playing: ${PlaybackWaits.whatIsActuallyPlaying(controller)}", state != null)
        assertFalse(state!!.hasVideo)
    }

    @Test
    fun aFileOnThePhoneGetsTheAppsSpeedAndItsPlaceIsKept() = runBlocking(Dispatchers.Main) {
        PhoneMediaFixtures.video(context, "totum-phone-video.mp4")
        val (_, playable) = playFirstMatching(PhoneMediaKind.VIDEO, "totum-phone-video")
        PlaybackWaits.awaitStateOf(controller, playable.item.id, TIMEOUT) { it.isPlaying }

        controller.setSpeedForNow(1.5f)
        controller.seekTo(1_500)
        val moved = PlaybackWaits.awaitStateOf(controller, playable.item.id, TIMEOUT) { it.positionMs >= 1_500 }

        assertNotNull(moved)
        assertEquals(1.5f, controller.player?.playbackParameters?.speed ?: 0f, 0.01f)
    }

    @Test
    fun thousandsOfPhotosAreReadAHundredAtATime() = runBlocking(Dispatchers.Main) {
        val total = withContext(Dispatchers.IO) { PhoneMediaFixtures.manyPhotos(context, SCALE) }
        assertTrue("fixtures should reach $SCALE, got $total", total >= SCALE)
        val browser = PhoneMediaBrowser(container.phoneMedia.device)

        browser.open(PhoneMediaQuery(PhoneMediaKind.PHOTO, folder = PhoneMediaFixtures.SCALE_FOLDER))
        assertEquals(PhoneMediaQuery.PAGE_SIZE, browser.state.value.entries.size)
        assertEquals(PhoneMediaQuery.PAGE_SIZE, browser.state.value.next)
        browser.more()
        browser.more()
        assertEquals(PhoneMediaQuery.PAGE_SIZE * 3, browser.state.value.entries.size)
        assertEquals(browser.state.value.entries.size, browser.state.value.entries.map { it.id }.toSet().size)
        while (browser.state.value.hasMore) browser.more()

        assertEquals(total, browser.state.value.entries.size)
        assertNull(browser.state.value.next)
    }

    private companion object {
        const val TIMEOUT = 60_000L
        const val SCALE = 330
    }
}
