package com.dewijones92.totum.data.ask.state

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppStateBuilderTest {

    private val built = AppStateBuilder.build(StateTestData.inputs())
    private val json = built.json
    private val text = json.toString()

    private fun rows(list: String): List<JsonObject> =
        (json.getValue(list) as? JsonArray ?: json.getValue(list).jsonObject.getValue("items").jsonArray)
            .map { it.jsonObject }

    private fun queueRow(id: String) = rows("queue").first { it.getValue("itemId").jsonPrimitive.content == id }

    @Test
    fun `no secret name or value leaves the pack`() {
        listOf(
            StateTestData.SECRET_FEED_KEY,
            StateTestData.SECRET_TOKEN,
            StateTestData.GOOGLE_SIG,
            "totumToken",
            "homeServerToken",
            "Bearer ",
        ).forEach { assertFalse("\"$it\" is in the pack", text.contains(it)) }
        assertFalse("no address of any kind is emitted for an item", text.contains("https://media.example"))
        assertFalse(text.contains("https://img.example"))
        assertFalse(text.contains("feeds.example/show.rss"))
        assertTrue(built.droppedKeys >= 1)
    }

    @Test
    fun `a subscription names its host and never its address`() {
        val subscription = rows("subscriptions").first { it.getValue("title").jsonPrimitive.content == "A Show" }
        assertEquals("feeds.example", subscription.getValue("host").jsonPrimitive.content)
        assertFalse(subscription.containsKey("url"))
        assertFalse(subscription.containsKey("id"))
    }

    @Test
    fun `a magic completed mark becomes played true and positions come as text`() {
        assertTrue(queueRow("v1").getValue("played").jsonPrimitive.boolean)
        assertNull(queueRow("v1")["positionMs"])
        val partway = queueRow("v2")
        assertFalse(partway.getValue("played").jsonPrimitive.boolean)
        assertEquals("4:00", partway.getValue("positionText").jsonPrimitive.content)
        assertEquals(240_000L, partway.getValue("positionMs").jsonPrimitive.content.toLong())
        assertEquals(40, partway.getValue("percentDone").jsonPrimitive.int)
        assertEquals("10:00", partway.getValue("durationText").jsonPrimitive.content)
    }

    @Test
    fun `an item known only by its progress is still listed`() {
        val orphan = rows("progress").first { it.getValue("itemId").jsonPrimitive.content == "orphan-item" }
        assertEquals("1:30", orphan.getValue("positionText").jsonPrimitive.content)
        assertNull(orphan["title"])
        val known = rows("progress").first { it.getValue("itemId").jsonPrimitive.content == "v2" }
        assertEquals("Peep Show S02E03 Mugging", known.getValue("title").jsonPrimitive.content)
    }

    @Test
    fun `the queue carries groups in runs, the playing place and episode numbers`() {
        val queue = json.getValue("queue").jsonObject
        assertEquals(2, queue.getValue("currentPosition").jsonPrimitive.int)
        val groups = queue.getValue("groups").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("Tennis", "Peep Show", "Tennis", "(added by you, no group)"),
            groups.map {
                it.getValue("title").jsonPrimitive.content
            }
        )
        assertEquals(2, groups[1].getValue("unplayed").jsonPrimitive.int + 1)
        val episode = queueRow("v2").getValue("episode").jsonObject
        assertEquals("S02E03", episode.getValue("code").jsonPrimitive.content)
        assertEquals(
            "S01E04",
            queueRow("torrent:0123456789abcdef0123456789abcdef01234567:4")
                .getValue("episode").jsonObject.getValue("code").jsonPrimitive.content
        )
        assertEquals("Tennis", queueRow("v1").getValue("queueGroup").jsonPrimitive.content)
        assertTrue(queueRow("v2").getValue("current").jsonPrimitive.boolean)
    }

    @Test
    fun `an item says which of the person's channel groups it belongs to, and whether it is starred`() {
        assertEquals(
            listOf("🎾 tennis"),
            queueRow("v1").getValue("sourceGroups").jsonArray.map { it.jsonPrimitive.content }
        )
        assertTrue(queueRow("v1").getValue("starred").jsonPrimitive.boolean)
        assertFalse(queueRow("v3").getValue("starred").jsonPrimitive.boolean)
        assertEquals(3, queueRow("v3").getValue("queuePosition").jsonPrimitive.int)
    }

    @Test
    fun `downloads list finished, running and failed copies with sizes and locks`() {
        val downloads = rows("downloads").associateBy { it.getValue("itemId").jsonPrimitive.content }
        val finished = downloads.getValue("v2")
        assertEquals("downloaded", finished.getValue("state").jsonPrimitive.content)
        assertEquals("5.0 MB", finished.getValue("sizeText").jsonPrimitive.content)
        assertTrue(finished.getValue("locked").jsonPrimitive.boolean)
        assertEquals(50, downloads.getValue("v1").getValue("percent").jsonPrimitive.int)
        assertEquals("failed", downloads.getValue("v3").getValue("state").jsonPrimitive.content)
        assertEquals("403 forbidden", downloads.getValue("v3").getValue("failure").jsonPrimitive.content)
        assertEquals(
            5_000_000L,
            queueRow("v2").getValue("downloaded").jsonObject.getValue("sizeBytes").jsonPrimitive.content.toLong()
        )
    }

    @Test
    fun `settings are expanded and listens and events carry local times and text durations`() {
        val settings = json.getValue("settings").jsonObject
        assertEquals(JsonPrimitive(true), settings.getValue("autoPlayNext"))
        assertEquals(JsonPrimitive(240L), settings.getValue("cellularMaxHeight"))
        assertTrue(settings.getValue("pins.v1") is JsonArray)
        assertFalse(settings.containsKey("homeServerToken"))
        val listen = rows("listens").single()
        assertEquals("3:20", listen.getValue("heardText").jsonPrimitive.content)
        assertTrue(listen.getValue("counted").jsonPrimitive.boolean)
        val at = listen.getValue("at").jsonPrimitive.content
        assertTrue(at.endsWith("Z") || at.contains("+"))
        val events = rows("events")
        assertEquals(3, events.size)
        assertEquals(listOf("route", "home", "queue"), events.map { it.getValue("tag").jsonPrimitive.content })
        assertTrue(events[0].getValue("message").jsonPrimitive.content.contains("expire=1").not())
    }

    @Test
    fun `the trail and the counts are in the snapshot`() {
        assertEquals(2, json.getValue("trail").jsonObject.getValue("position").jsonPrimitive.int)
        val counts = json.getValue("snapshot").jsonObject.getValue("counts").jsonObject
        assertEquals(4, counts.getValue("queue").jsonPrimitive.int)
        assertEquals(3, counts.getValue("events").jsonPrimitive.int)
        assertEquals(built.counts.getValue("downloads"), counts.getValue("downloads").jsonPrimitive.int)
    }

    @Test
    fun `every id in the pack resolves to an item the phone can show`() {
        val ids = rows("queue").map { it.getValue("itemId").jsonPrimitive.content } +
            rows("history").map { it.getValue("itemId").jsonPrimitive.content } +
            rows("downloads").map { it.getValue("itemId").jsonPrimitive.content }
        assertTrue(ids.all { it in built.items })
        assertEquals("Day 2 highlights", built.items.getValue("v1").item.title)
    }

    @Test
    fun `long lists are cut at their newest rows and the pack says so`() {
        val many = (1..AppStateBuilder.MAX_LISTENS + 5).map {
            ListenFact(StateTestData.NOW - it * 1_000L, "t$it", null, null, null, "video", false, 1_000, null, false)
        }
        val big = AppStateBuilder.build(StateTestData.inputs(listens = many))
        val kept = big.json.getValue("listens").jsonArray
        assertEquals(AppStateBuilder.MAX_LISTENS, kept.size)
        assertEquals("t1", kept.first().jsonObject.getValue("title").jsonPrimitive.content)
        assertTrue(big.notes.any { it.startsWith("listens: newest ${AppStateBuilder.MAX_LISTENS}") })
    }

    @Test
    fun `a flood of unlocks does not push the exsurge and alarm history out of the pack`() {
        val unlocks = (1..600).map {
            StatFact(
                StateTestData.NOW - it * 1_000L,
                "phone-unlock",
                """{"type":"unlock"}"""
            )
        }
        val older = (1..10).map {
            StatFact(StateTestData.NOW - 10_000_000L - it * 1_000L, "exsurge", """{"type":"exsurge","n":$it}""")
        }
        val alarm = StatFact(StateTestData.NOW - 20_000_000L, "alarm", """{"type":"alarm"}""")

        val big = AppStateBuilder.build(StateTestData.inputs(stats = unlocks + older + alarm))
        val kept = big.json.getValue(
            "otherStats"
        ).jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }

        assertEquals(10, kept.count { it == "exsurge" })
        assertEquals(1, kept.count { it == "alarm" })
        assertEquals(AppStateBuilder.MAX_OTHER_STATS_PER_TYPE, kept.count { it == "phone-unlock" })
        assertTrue(
            big.notes.any {
                it.startsWith("otherStats: newest ${AppStateBuilder.MAX_OTHER_STATS_PER_TYPE} of each kind")
            }
        )
    }

    @Test
    fun `durations and sizes read the way a person says them`() {
        assertEquals("0:05", AppStateBuilder.durationText(5_000))
        assertEquals("1:02:03", AppStateBuilder.durationText(3_723_000))
        assertEquals("2.50 GB", AppStateBuilder.sizeText(2_500_000_000))
        assertEquals("12 KB", AppStateBuilder.sizeText(12_000))
    }
}
