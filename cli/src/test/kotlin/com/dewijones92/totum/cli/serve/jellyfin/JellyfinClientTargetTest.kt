package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.cli.serve.jellyfin.FakeJellyfinSessions.Companion.list
import com.dewijones92.totum.cli.serve.jellyfin.FakeJellyfinSessions.Companion.session
import com.dewijones92.totum.cli.serve.targets.PlayRequest
import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.targets.SoundSettings
import com.dewijones92.totum.data.targets.TargetKind
import com.dewijones92.totum.data.targets.TargetPlays
import com.dewijones92.totum.data.targets.TargetPower
import com.dewijones92.totum.data.targets.TroubleKind
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinClientTargetTest {

    private val jellyfin = FakeJellyfinSessions()
    private var now = 1_000_000L
    private val logged = mutableListOf<String>()
    private val ended = mutableListOf<Boolean>()
    private val lost = mutableListOf<String>()

    private val api = JellyfinSessionApi(Jellyfin(OkHttpClient(), jellyfin.base, KEY, emptyMap()))
    private val board = SessionBoard(api) { now }
    private val client = JellyfinClient("Living room Jellyfin", "Test TV")
    private val player = JellyfinClientPlayer("Living room Jellyfin", client, board, api, { now }, polls = false).also {
        it.onEnded = { failed -> ended += failed }
        it.onLost = { reason -> lost += reason }
    }
    private val link = JellyfinClientLink("Living room Jellyfin", client, board)

    init {
        Diag.sink = Diag.Sink { _, _, message, _ -> logged += message }
    }

    @After
    fun tidy() {
        jellyfin.close()
        Diag.sink = Diag.Sink { _, _, _, _ -> }
    }

    private fun request(itemId: String? = "jellyfin:$FILM", startAtMs: Long = 0) = PlayRequest(
        "http://jellyfin.invalid/Videos/$FILM/stream?static=true&api_key=$KEY",
        "Test TV",
        SoundSettings(),
        startAtMs,
        true,
        "A Test Film",
        null,
        itemId,
    )

    private fun look(json: String) {
        jellyfin.sessions = json
        now += 1_000
        player.poll()
    }

    private fun playingFilm() {
        jellyfin.sessions = list(session())
        player.play(request())
    }

    @Test
    fun `an app that is not open is asleep, and picking it says to open it by hand`() = runBlocking {
        jellyfin.sessions = list(session(controllable = false))

        assertNull(link.connect())

        assertEquals(TroubleKind.SWITCH_ON, link.unreachable?.kind)
        assertTrue(link.switchedOnByHand)
        assertEquals(TargetPower.ASLEEP, link.power)
        assertFalse(link.present())
    }

    @Test
    fun `an app that is open and controllable is on, and the link names the device`() = runBlocking {
        jellyfin.sessions = list(session())

        assertEquals("Test TV", link.connect())

        assertNull(link.unreachable)
        assertEquals(TargetPower.ON, link.power)
        assertTrue(link.present())
    }

    @Test
    fun `no session for the device at all is the same as a closed app`() = runBlocking {
        jellyfin.sessions = list(session(deviceName = "Some other device"))

        assertNull(link.connect())
        assertEquals(TargetPower.ASLEEP, link.power)
    }

    @Test
    fun `Jellyfin not answering is not connected, not a switch-on instruction`() = runBlocking {
        jellyfin.sessionsAnswer = 500

        assertNull(link.connect())

        assertEquals(TroubleKind.NOT_CONNECTED, link.unreachable?.kind)
    }

    @Test
    fun `a play hands Jellyfin the item id and the start position, with the key in the header only`() {
        jellyfin.sessions = list(session())

        player.play(request(startAtMs = 90_000))

        val post = jellyfin.lastPost()
        assertEquals(
            "/Sessions/0123456789abcdef0123456789abcdef/Playing?playCommand=PlayNow&itemIds=$FILM" +
                "&startPositionTicks=900000000",
            post.target,
        )
        assertEquals("MediaBrowser Token=\"$KEY\"", post.authorization)
        assertTrue(ended.isEmpty())
        assertTrue(logged.joinToString("\n"), logged.none { it.contains(KEY) })
    }

    @Test
    fun `a player is only ever offered Jellyfin items and says so`() {
        assertEquals(TargetPlays.JELLYFIN_ONLY, player.plays)
    }

    @Test
    fun `a YouTube item is refused before anything is sent, with the reason as the failure`() {
        jellyfin.sessions = list(session())

        player.play(request(itemId = "dQw4w9WgXcQ"))

        assertEquals(listOf(true), ended)
        assertTrue(player.failure!!, player.failure!!.contains("not a Jellyfin item"))
        assertTrue(jellyfin.posts().isEmpty())
    }

    @Test
    fun `a play while the app is not open fails with that reason and sends nothing`() {
        jellyfin.sessions = list(session(controllable = false))

        player.play(request())

        assertEquals(listOf(true), ended)
        assertTrue(player.failure!!, player.failure!!.contains("is not open"))
        assertTrue(jellyfin.posts().isEmpty())
    }

    @Test
    fun `Jellyfin refusing the play is a failure that names the answer`() {
        jellyfin.sessions = list(session())
        jellyfin.commandAnswer = 404

        player.play(request())

        assertEquals(listOf(true), ended)
        assertTrue(player.failure!!, player.failure!!.contains("404"))
    }

    @Test
    fun `what the client reports becomes the position, length and volume of the target`() {
        playingFilm()

        look(list(session(nowPlaying = FILM, positionTicks = 6_085_000_000, volume = 40)))

        assertEquals(608_500L, player.positionMs)
        assertEquals(10_200_000L, player.durationMs)
        assertEquals(40, player.volumePercent)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `a client that goes quiet for the whole queue item is over, and a finished item moves the queue on`() {
        playingFilm()
        look(list(session(nowPlaying = FILM, positionTicks = 5_000_000)))

        look(list(session(nowPlaying = null)))

        assertEquals(listOf(false), ended)
    }

    @Test
    fun `the client moving on to another item by itself ends ours, so the queue decides what is next`() {
        playingFilm()
        look(list(session(nowPlaying = FILM, positionTicks = 5_000_000)))

        look(list(session(nowPlaying = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", nowPlayingName = "Up next")))

        assertEquals(listOf(false), ended)
        assertTrue(logged.joinToString("\n"), logged.any { it.contains("playing Up next instead of") })
    }

    @Test
    fun `an item that never starts within twenty seconds is a failure, not an endless wait`() {
        playingFilm()

        look(list(session(nowPlaying = null)))
        assertTrue("still waiting for the client to start it", ended.isEmpty())

        now += 21_000
        look(list(session(nowPlaying = null)))

        assertEquals(listOf(true), ended)
    }

    @Test
    fun `a client that stops being listed is lost after three missed looks`() {
        playingFilm()
        look(list(session(nowPlaying = FILM, positionTicks = 5_000_000)))

        repeat(3) { look(list()) }

        assertEquals(1, lost.size)
        assertTrue(lost.single(), lost.single().contains("stopped answering"))
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `an app closed mid-item is lost, not finished`() {
        playingFilm()
        look(list(session(nowPlaying = FILM, positionTicks = 5_000_000)))

        repeat(3) { look(list(session(controllable = false))) }

        assertEquals(1, lost.size)
        assertTrue(ended.isEmpty())
    }

    @Test
    fun `pause, resume, seek, volume and stop are the client's own play state commands`() {
        playingFilm()
        look(list(session(nowPlaying = FILM, positionTicks = 5_000_000)))
        val sid = "0123456789abcdef0123456789abcdef"

        player.pause(true)
        player.pause(false)
        player.seek(120_000)
        player.volume(35)
        player.stop()

        val sent = jellyfin.posts().drop(1)
        assertEquals(
            listOf(
                "/Sessions/$sid/Playing/Pause",
                "/Sessions/$sid/Playing/Unpause",
                "/Sessions/$sid/Playing/Seek?seekPositionTicks=1200000000",
                "/Sessions/$sid/Command",
                "/Sessions/$sid/Playing/Stop",
            ),
            sent.map { it.target },
        )
        assertEquals("""{"Name":"SetVolume","Arguments":{"Volume":"35"}}""", sent[3].body)
        assertEquals(35, player.volumePercent)
    }

    @Test
    fun `a stop, a pause or a seek with nothing loaded from Totum sends nothing`() {
        jellyfin.sessions = list(session(nowPlaying = FILM))
        now += 1_000
        board.sessions()

        player.stop()
        player.pause(true)

        assertTrue(jellyfin.posts().isEmpty())
    }

    @Test
    fun `the key never reaches the log`() {
        playingFilm()
        look(list(session(nowPlaying = FILM, positionTicks = 5_000_000)))
        player.pause(true)
        player.stop()
        jellyfin.commandAnswer = 500
        player.volume(10)

        assertTrue(logged.isNotEmpty())
        assertTrue(logged.joinToString("\n"), logged.none { it.contains(KEY) })
    }

    @Test
    fun `the Jellyfin source lists each configured client as a TV target, open or not`() = runBlocking {
        jellyfin.sessions = list(session(deviceName = "Another one", controllable = true))
        val source = JellyfinClientSource(JellyfinClient.fromSpec("Living room Jellyfin=Test TV"), board, api) { now }

        val found = source.find(fresh = true)

        val target = found.single().target
        assertEquals("jellyfin-living-room-jellyfin", target.id)
        assertEquals("Living room Jellyfin", target.label)
        assertEquals(TargetKind.TV, target.kind)
        val kit = found.single().kit()
        assertEquals(TargetPlays.JELLYFIN_ONLY, kit.player.plays)
        assertEquals(TargetPower.ASLEEP, kit.link.power)
    }

    @Test
    fun `the source still lists the clients when Jellyfin cannot be asked`() = runBlocking {
        jellyfin.sessionsAnswer = 500
        val source = JellyfinClientSource(JellyfinClient.fromSpec("Fire Stick"), board, api) { now }

        val found = source.find(fresh = true)

        assertEquals(listOf("jellyfin-fire-stick"), found.map { it.target.id })
    }

    @Test
    fun `the clients setting is label equals what the device name contains, and a bare name is both`() {
        val clients = JellyfinClient.fromSpec("Living room=AFT; Fire Stick ;=nothing;empty=")

        assertEquals(listOf("Living room", "Fire Stick"), clients.map { it.label })
        assertTrue(clients[0].matches(parse(session(deviceName = "AFTMM"))))
        assertTrue(clients[1].matches(parse(session(deviceName = "my fire stick 4k"))))
        assertFalse(clients[0].matches(parse(session(deviceName = "iPhone"))))
        assertEquals(emptyList<JellyfinClient>(), JellyfinClient.fromSpec(null))
    }

    @Test
    fun `an exact device id matches even when the name does not`() {
        val byId = JellyfinClient("Pinned", "dev-77")

        assertTrue(byId.matches(parse(session(deviceId = "dev-77", deviceName = "Renamed"))))
    }

    @Test
    fun `the closest session is the controllable one, then the most recently active`() {
        val sessions = JellyfinSessionParser.parse(
            list(
                session(sessionId = "a".repeat(32), controllable = false, lastActivity = "2026-01-03T00:00:00Z"),
                session(sessionId = "b".repeat(32), controllable = true, lastActivity = "2026-01-01T00:00:00Z"),
                session(sessionId = "c".repeat(32), controllable = true, lastActivity = "2026-01-02T00:00:00Z"),
            ),
        )!!

        assertEquals("c".repeat(32), client.best(sessions)?.id)
    }

    @Test
    fun `the live idle session shape parses, and is not controllable`() {
        val text = resource("sessions-idle-live-shape.json")

        val sessions = JellyfinSessionParser.parse(text)!!

        val only = sessions.single()
        assertNull(only.nowPlayingId)
        assertFalse(only.controllable)
        assertEquals("Test device", only.deviceName)
        assertEquals(0L, only.positionMs)
    }

    @Test
    fun `a playing session shape, written from the server's own OpenAPI, parses with position, length and volume`() {
        val only = JellyfinSessionParser.parse(resource("sessions-playing-synthetic.json"))!!.single()

        assertTrue(only.controllable)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", only.nowPlayingId)
        assertEquals("A Test Film", only.nowPlayingName)
        assertEquals(608_500L, only.positionMs)
        assertEquals(10_200_000L, only.durationMs)
        assertEquals(40, only.volume)
        assertFalse(only.paused)
    }

    @Test
    fun `an answer that is not a session list is not read as an empty one`() {
        assertNull(JellyfinSessionParser.parse("<html>login</html>"))
        assertNull(JellyfinSessionParser.parse("""{"Items":[]}"""))
        assertNotNull(JellyfinSessionParser.parse("[]"))
    }

    @Test
    fun `no Jellyfin client is a target unless Jellyfin and the clients setting are both there`() = runBlocking {
        val jf = Jellyfin(OkHttpClient(), jellyfin.base, KEY, emptyMap())
        val setting = mapOf("TOTUM_JELLYFIN_CLIENTS" to "Fire Stick")

        assertEquals(0, jellyfinClientsFrom(null, setting::get).find(fresh = true).size)
        assertEquals(0, jellyfinClientsFrom(jf) { null }.find(fresh = true).size)
        val targets = jellyfinClientsFrom(jf, setting::get).find(fresh = true)
        assertEquals(listOf("jellyfin-fire-stick"), targets.map { it.target.id })
    }

    private fun parse(json: String): JellyfinSession = JellyfinSessionParser.parse("[$json]")!!.single()

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResource("/jellyfin-sessions/$name")) { "missing $name" }.readText()

    private companion object {
        const val KEY = "SECRET-JELLYFIN-KEY-0123456789abcdef"
        const val FILM = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
