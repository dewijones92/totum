package com.dewijones92.totum.data.diagnostics

import com.dewijones92.totum.common.Breadcrumbs
import com.dewijones92.totum.common.HomeServerHosts
import com.dewijones92.totum.data.ask.state.StateTestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class StateDumpTest {

    private val homeToken = "HOME-DEVICE-TOKEN-8c1d2e3f4a5b"
    private val prowlarrKey = "PROWLARR-KEY-77aa88bb99cc"
    private val youtubeRefresh = "1//0gYOUTUBE-REFRESH-TOKEN-zzzz1111"
    private val youtubeAccess = "ya29.YOUTUBE-ACCESS-TOKEN-yyyy2222"
    private val cookieValue = "SAPISID-COOKIE-VALUE-xxxx3333"
    private val homeHost = "myhome.example.net"

    private val preferences: Map<String, Map<String, Any?>> = mapOf(
        "totum_settings" to mapOf(
            "home_server" to homeHost,
            "home_server_token" to homeToken,
            "prowlarr_api_key" to prowlarrKey,
            "gapless_queue" to true,
            "wifi_max_height" to 1080,
        ),
        "youtube_account" to mapOf("access_token" to youtubeAccess, "refresh_token" to youtubeRefresh),
        "totum_visitor" to mapOf("visitor_data" to cookieValue),
        "totum_drive_backup" to mapOf("folder" to "content://com.google.android.apps.docs/tree/abcdef"),
        "exsurge" to mapOf("state.v1" to """{"phase":"IDLE","stepsToday":1200,"authToken":"$homeToken"}"""),
        "daily_alarms" to mapOf("alarms.v1" to """[{"id":"pickup","time":"17:30"}]"""),
        "totum_pins" to mapOf("tags" to setOf("a", "b")),
    )

    private val events = StateTestData.events + Breadcrumbs.Entry(
        StateTestData.NOW,
        "home",
        "token $homeToken sent to https://totum.$homeHost/fetch and cookie $cookieValue",
        9,
    )

    private fun built() = StateDump.build(
        StateTestData.inputs(
            events = events,
            secrets = listOf(homeToken, prowlarrKey),
            hosts = HomeServerHosts.allFor(listOf(homeHost)),
        ),
        preferences,
    )

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun `the dump is gzip and holds the backup, the ask state and the preferences`() {
        val dump = built()

        assertEquals(0x1f, dump.gzipped[0].toInt() and 0xff)
        assertEquals(0x8b, dump.gzipped[1].toInt() and 0xff)
        val text = gunzip(dump.gzipped)
        listOf("\"format\"", "\"backup\"", "\"state\"", "\"preferences\"").forEach {
            assertTrue("$it missing", text.contains(it))
        }
        assertEquals(StateDump.FORMAT, dump.json["format"].toString().toInt())
    }

    @Test
    fun `no sign-in token, key, cookie or home-server address survives anywhere in the dump`() {
        val text = gunzip(built().gzipped)

        listOf(
            homeToken,
            prowlarrKey,
            youtubeRefresh,
            youtubeAccess,
            cookieValue,
            homeHost,
            StateTestData.SECRET_TOKEN,
            StateTestData.GOOGLE_SIG,
            StateTestData.SECRET_FEED_KEY,
        ).forEach { secret ->
            assertFalse("\"$secret\" is in the dump", text.contains(secret))
        }
    }

    @Test
    fun `credential files are left out whole, not just scrubbed`() {
        val text = gunzip(built().gzipped)

        listOf(
            "youtube_account",
            "totum_visitor",
            "totum_drive_backup",
            "content://com.google.android.apps.docs",
        ).forEach {
            assertFalse("\"$it\" is in the dump", text.contains(it))
        }
        assertTrue(text.contains("exsurge"))
        assertTrue(text.contains("daily_alarms"))
    }

    @Test
    fun `preferences keep their types and a stored JSON value is read as JSON`() {
        val prefs = built().json["preferences"].toString()

        assertTrue(prefs, prefs.contains("\"gapless_queue\":true"))
        assertTrue(prefs, prefs.contains("\"wifi_max_height\":1080"))
        assertTrue(prefs, prefs.contains("\"stepsToday\":1200"))
        assertTrue(prefs, prefs.contains("\"time\":\"17:30\""))
        assertTrue(prefs, prefs.contains("\"tags\":[\"a\",\"b\"]"))
    }

    @Test
    fun `the backup in the dump can be restored and carries the live queue`() {
        val backup = StateDump.backupOf(StateDump.unpack(built().gzipped))

        assertNotNull(backup)
        assertEquals(StateTestData.queue.map { it.item.item.id.value }, backup!!.queue.map { it.itemId })
        assertEquals(StateTestData.backup.playlists.map { it.name }, backup.playlists.map { it.name })
        assertEquals(StateTestData.backup.history.map { it.itemId }, backup.history.map { it.itemId })
        assertEquals(StateTestData.backup.progress.map { it.itemId }, backup.progress.map { it.itemId })
        assertEquals(StateTestData.backup.groups.map { it.name }, backup.groups.map { it.name })
        assertFalse(backup.settings.containsKey("homeServerToken"))
        assertEquals("true", backup.settings["autoPlayNext"])
    }

    @Test
    fun `something that is not a dump has no backup`() {
        assertNull(StateDump.backupOf(StateDump.unpack(StateDump.packText("""{"hello":1}"""))))
    }
}
