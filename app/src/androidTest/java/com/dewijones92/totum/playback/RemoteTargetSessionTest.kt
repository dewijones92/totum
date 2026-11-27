package com.dewijones92.totum.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.dewijones92.totum.MainActivity
import com.dewijones92.totum.TotumApplication
import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.data.backup.BackupItem
import com.dewijones92.totum.data.home.HomeAnswer
import com.dewijones92.totum.data.home.TargetsApi
import com.dewijones92.totum.data.targets.Requester
import com.dewijones92.totum.data.targets.Target
import com.dewijones92.totum.data.targets.TargetAction
import com.dewijones92.totum.data.targets.TargetColours
import com.dewijones92.totum.data.targets.TargetControl
import com.dewijones92.totum.data.targets.TargetEntry
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetQueueRequest
import com.dewijones92.totum.data.targets.TargetState
import com.dewijones92.totum.data.targets.TargetsAnswer
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.MediaKind
import com.dewijones92.totum.domain.SourceId
import com.dewijones92.totum.support.SilentWav
import com.dewijones92.totum.targets.InMemoryPlayOn
import com.dewijones92.totum.targets.InMemoryTargetColours
import com.dewijones92.totum.targets.RemoteSessionBridge
import com.dewijones92.totum.targets.TargetsRepository
import com.dewijones92.totum.ui.mainThreadScope
import com.dewijones92.totum.ui.queue.statusRes
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RemoteTargetSessionTest {

    @get:Rule
    val activity = ActivityScenarioRule(MainActivity::class.java)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val app = context.applicationContext as TotumApplication
    private val scope = mainThreadScope()
    private val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
    private val controllers = mutableListOf<MediaController>()
    private val art = ArtServer()

    private class Pi(var targets: List<TargetState>) : TargetsApi {
        val controls = CopyOnWriteArrayList<TargetControl>()

        override suspend fun targets() = HomeAnswer.Ok(TargetsAnswer(targets))

        override suspend fun queue(id: String, request: TargetQueueRequest): HomeAnswer<TargetState> =
            HomeAnswer.No("unused")

        override suspend fun control(id: String, control: TargetControl): HomeAnswer<TargetState> {
            controls += control
            return HomeAnswer.No("unused")
        }
    }

    private class ArtServer {
        private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val url: String get() = "http://127.0.0.1:${server.localPort}/art.png"

        init {
            val body = ByteArrayOutputStream().also { out ->
                Bitmap.createBitmap(ART_SIZE, ART_SIZE, Bitmap.Config.ARGB_8888)
                    .also { it.eraseColor(Color.rgb(0, 137, 176)) }
                    .compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()
            Thread {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { socket ->
                            socket.getInputStream().bufferedReader().readLine()
                            val head =
                                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: ${body.size}\r\n\r\n"
                            socket.getOutputStream().apply {
                                write(head.toByteArray())
                                write(body)
                                flush()
                            }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun close() = server.close()
    }

    @Before
    fun waitForThePhonesSession() {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (app.container.playbackController.player == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
        }
        assertTrue("the phone's own controller never connected", app.container.playbackController.player != null)
        Breadcrumbs.clear()
    }

    @After
    fun tidy() {
        scope.cancel()
        art.close()
        instrumentation.runOnMainSync { controllers.forEach { it.release() } }
        RemoteSessionHost.publish(null)
    }

    private fun entry(id: String, title: String) = TargetEntry(
        BackupItem(
            id,
            "feed",
            title,
            author = "The Rest Is Football",
            thumbnailUrl = art.url,
            mediaUrl = "https://example.com/$id.mp3",
            contentKind = "STANDARD",
            playbackType = "AUDIO",
        ),
    )

    private val lg = TargetState(
        Target("lg-tv", "LG TV", TargetKind.TV),
        entries = listOf(entry("a", "Episode one"), entry("b", "Episode two"), entry("c", "Episode three")),
        at = 1,
        playing = true,
        positionMs = 125_000,
        durationMs = 600_000,
    )

    private fun hold() {
        val ms = InstrumentationRegistry.getArguments().getString("holdMs")?.toLongOrNull() ?: 0
        if (ms > 0) Thread.sleep(ms)
    }

    private fun select(state: TargetState): Pair<Pi, TargetsRepository> {
        val pi = Pi(listOf(state))
        val repo = TargetsRepository(
            pi,
            { Requester("me", "My phone") },
            InMemoryTargetColours(),
            scope,
            playOn = InMemoryPlayOn(),
        )
        runBlocking { repo.refresh("test") }
        repo.select("lg-tv")
        RemoteSessionBridge(repo, scope, { label, s -> "$label · ${context.getString(s.statusRes())}" }).start()
        return pi to repo
    }

    private fun logged(fragment: String) = Breadcrumbs.snapshot().any { fragment in it.message }

    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (!check() && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MS)
        assertTrue("timed out waiting for $what", check())
    }

    private fun connectToTheDevicesSession(): MediaController {
        waitFor("the second session to open") { logged("opened the second session") }
        val hints = Bundle().apply { putBoolean(RemoteSessionHost.HINT_REMOTE_SESSION, true) }
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync {
            future = MediaController.Builder(context, token).setConnectionHints(hints).buildAsync()
        }
        return checkNotNull(future).get(WAIT_MS, TimeUnit.MILLISECONDS).also { controllers += it }
    }

    private fun onMain(read: () -> Unit) = instrumentation.runOnMainSync(read)

    @Test
    fun theSelectedDeviceIsASecondSessionWithItsTitleArtAndDeviceName() {
        select(lg)
        val remote = connectToTheDevicesSession()

        var title: CharSequence? = null
        var artist: CharSequence? = null
        var length = 0L
        var playWhenReady = false
        var next = false
        var back = false
        onMain {
            title = remote.mediaMetadata.title
            artist = remote.mediaMetadata.artist
            length = remote.duration
            playWhenReady = remote.playWhenReady
            next = remote.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT)
            back = remote.isCommandAvailable(Player.COMMAND_SEEK_BACK)
        }

        assertEquals("Episode two", title.toString())
        assertTrue("the device and its state are on the second line: $artist", "📺 LG TV · ▶️ Playing" in "$artist")
        assertTrue("the author is there too: $artist", "The Rest Is Football" in artist.toString())
        assertEquals(600_000L, length)
        assertTrue(playWhenReady)
        assertTrue("next is offered", next)
        assertTrue("back 10 is offered", back)
    }

    @Test
    fun thePhonesOwnSessionIsUntouchedWhileADeviceIsShown() {
        select(lg)
        connectToTheDevicesSession()

        val phone = app.container.playbackController.player
        var item: String? = "unset"
        instrumentation.runOnMainSync { item = phone?.currentMediaItem?.mediaId }

        assertFalse(
            "the device's items must never appear in the phone's player: $item",
            item in setOf("a", "b", "c"),
        )
    }

    @Test
    fun theLockScreenControlsReachTheDeviceAsTheSameActionsTheBarSends() {
        val (pi, _) = select(lg)
        val remote = connectToTheDevicesSession()

        onMain { remote.pause() }
        waitFor("pause to reach the Pi") { pi.controls.any { it.action == TargetAction.PAUSE } }
        onMain { remote.seekForward() }
        waitFor("the seek to reach the Pi") { pi.controls.any { it.action == TargetAction.SEEK } }
        onMain { remote.seekToNextMediaItem() }
        waitFor("next to reach the Pi") { pi.controls.any { it.action == TargetAction.NEXT } }

        val seek = pi.controls.first { it.action == TargetAction.SEEK }.positionMs ?: -1
        assertTrue("forward 30 from about 125s, not $seek", seek in 155_000..165_000)
    }

    @Test
    fun theNotificationIsTheDevicesInItsColourWithItsNameAndControls() {
        select(lg)
        connectToTheDevicesSession()
        val manager = context.getSystemService(NotificationManager::class.java)

        waitFor("the second notification") { manager.activeNotifications.any { it.id == REMOTE_NOTIFICATION_ID } }
        val shown = manager.activeNotifications.first { it.id == REMOTE_NOTIFICATION_ID }.notification
        waitFor("the device's name and colour on it") {
            val n = manager.activeNotifications.first { it.id == REMOTE_NOTIFICATION_ID }.notification
            n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() == "📺 LG TV"
        }
        val tinted = manager.activeNotifications.first { it.id == REMOTE_NOTIFICATION_ID }.notification

        assertEquals("Episode two", shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(TargetColours.assign(listOf(lg.target), emptyMap()).getValue("lg-tv").toInt(), tinted.color)
        val actions = tinted.actions?.size ?: 0
        assertTrue("play or pause, back, forward and next: $actions", actions >= MIN_ACTIONS)
        assertFalse(
            "the phone's own notification id must not carry the device's name",
            manager.activeNotifications.any {
                it.id == PHONE_NOTIFICATION_ID &&
                    it.notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() == "📺 LG TV"
            },
        )
        hold()
    }

    @Test
    fun choosingThePhoneAgainTakesTheSecondSessionAway() {
        val (_, repo) = select(lg)
        connectToTheDevicesSession()
        val manager = context.getSystemService(NotificationManager::class.java)
        waitFor("the second notification") { manager.activeNotifications.any { it.id == REMOTE_NOTIFICATION_ID } }

        repo.select(null)

        waitFor("the second session to close") { logged("closed the second session") }
        waitFor("its notification to go") { manager.activeNotifications.none { it.id == REMOTE_NOTIFICATION_ID } }
        assertFalse(RemoteSessionHost.current != null)
    }

    private fun phoneHolds(playing: Boolean) {
        val file = File(context.cacheDir, "remote-session-phone.wav").apply { writeBytes(SilentWav.bytes(90)) }
        val item = MediaItem(
            MediaItemId("phone-own"),
            SourceId("test"),
            "On the phone",
            publishedAt = null,
            duration = null,
        )
        instrumentation.runOnMainSync {
            app.container.playbackController.play(item, kind = MediaKind.PODCAST, localPath = file.absolutePath)
        }
        waitFor("the phone's own item to start") {
            app.container.playbackController.state.value?.let { it.itemId == item.id && it.isPlaying } == true
        }
        if (!playing) {
            instrumentation.runOnMainSync { app.container.playbackController.setPlaying(false) }
            waitFor("the phone to pause") { app.container.playbackController.state.value?.isPlaying == false }
        }
    }

    private fun postedIds(): Set<Int> = context.getSystemService(NotificationManager::class.java)
        .activeNotifications
        .map { it.id }
        .filter { it == PHONE_NOTIFICATION_ID || it == REMOTE_NOTIFICATION_ID }
        .toSet()

    @Test
    fun aPausedPhoneItemAndAShownDeviceAreOneCardNotTwo() {
        phoneHolds(playing = false)
        select(lg)
        connectToTheDevicesSession()
        waitFor("the device's notification") { REMOTE_NOTIFICATION_ID in postedIds() }
        Thread.sleep(SETTLE_MS)
        hold()

        assertEquals(
            "only the device's card is posted, not the phone's too",
            setOf(REMOTE_NOTIFICATION_ID),
            postedIds()
        )
    }

    @Test
    fun aPlayingPhoneItemKeepsItsCardAndTheIdleDeviceStepsAside() {
        phoneHolds(playing = true)
        select(lg.copy(playing = false))
        connectToTheDevicesSession()
        waitFor("the phone's notification") { PHONE_NOTIFICATION_ID in postedIds() }
        Thread.sleep(SETTLE_MS)
        hold()

        assertEquals("the playing phone is the only card", setOf(PHONE_NOTIFICATION_ID), postedIds())
    }

    @Test
    fun bothPlayingIsTheChosenDevicesCardOnly() {
        phoneHolds(playing = true)
        select(lg)
        connectToTheDevicesSession()
        waitFor("the device's notification") { REMOTE_NOTIFICATION_ID in postedIds() }
        Thread.sleep(SETTLE_MS)
        hold()

        assertEquals("the chosen device wins a tie", setOf(REMOTE_NOTIFICATION_ID), postedIds())
    }

    @Test
    fun bothPausedIsTheChosenDevicesCardOnly() {
        phoneHolds(playing = false)
        select(lg.copy(playing = false))
        connectToTheDevicesSession()
        waitFor("the device's notification") { REMOTE_NOTIFICATION_ID in postedIds() }
        Thread.sleep(SETTLE_MS)
        hold()

        assertEquals("the chosen device wins when neither plays", setOf(REMOTE_NOTIFICATION_ID), postedIds())
    }

    @Test
    fun aPhoneItemAloneIsItsOwnCardAsBefore() {
        phoneHolds(playing = false)
        waitFor("the phone's notification") { PHONE_NOTIFICATION_ID in postedIds() }
        Thread.sleep(SETTLE_MS)
        hold()

        assertEquals("with no device chosen the phone is the only card", setOf(PHONE_NOTIFICATION_ID), postedIds())
    }

    @Test
    fun theDeviceGoingAwayBringsThePhonesCardBack() {
        phoneHolds(playing = false)
        val (_, repo) = select(lg)
        connectToTheDevicesSession()
        waitFor("the device's notification") { REMOTE_NOTIFICATION_ID in postedIds() }

        repo.select(null)

        waitFor("the phone's card to return and the device's to go") { postedIds() == setOf(PHONE_NOTIFICATION_ID) }
    }

    private companion object {
        const val WAIT_MS = 15_000L
        const val POLL_MS = 100L
        const val REMOTE_NOTIFICATION_ID = 1002
        const val PHONE_NOTIFICATION_ID = 1001
        const val MIN_ACTIONS = 3
        const val ART_SIZE = 256
        const val SETTLE_MS = 3_000L
    }
}
