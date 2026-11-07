package com.dewijones92.totum.cli.serve

import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskKind
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.AskSource
import com.dewijones92.totum.data.ask.AskTurn
import com.dewijones92.totum.data.ask.Suggestion
import com.dewijones92.totum.data.ask.Taste
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.ask.state.AskStateEnvelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RoutedAskTest {

    private class FakeBrain(var decision: RouteDecision) : Brain {
        val webCalls = mutableListOf<AskRequest>()
        val appCalls = mutableListOf<Pair<String, List<AskTurn>>>()
        var appFails = false
        var routed = 0

        override suspend fun route(turns: List<AskTurn>): RouteDecision {
            routed++
            return decision
        }

        override suspend fun suggest(request: AskRequest): AskAnswer {
            webCalls += request
            return AskAnswer(
                listOf(Suggestion(AskKind.FILM, "Zog", 2018, "dragons", "Zog 2018")),
                "web says zog",
            )
        }

        override suspend fun answerFromApp(question: String, earlier: List<AskTurn>, state: StoredState): AppReply {
            appCalls += question to earlier
            if (appFails) throw BrainFailure("jq fell over")
            return AppReply(
                "you have 3",
                listOf(AppCard("Unplayed", "three of them", listOf("v1", "ghost", "v2"))),
                toolCalls = 4,
                denied = 1,
            )
        }

        override suspend fun vet(request: VetRequest): VetAnswer = VetAnswer(emptyList())
    }

    private val states = AskStates()
    private val envelope = AskStateEnvelope(
        JsonObject(emptyMap()),
        buildJsonObject {
            put("note", "x")
        }.let { base ->
            JsonObject(
                base + mapOf(
                    "queue" to kotlinx.serialization.json.Json.parseToJsonElement(
                        """{"items":[{"itemId":"v1"},{"itemId":"v2"}]}""",
                    ),
                ),
            )
        },
    )

    private fun brain(route: AskSource, appQ: String = "", webQ: String = "", needs: Boolean = false) =
        FakeBrain(RouteDecision(route, appQ, webQ, needs, "because"))

    private fun asking(vararg turns: AskTurn, id: String? = "snapshot-0001") =
        AskRequest(
            turns.toList(),
            have = listOf("a title I own"),
            taste = Taste(channels = listOf("My Channel")),
            stateId = id
        )

    @Test
    fun `an ask with no snapshot is the old web ask, with no routing`() = runBlocking {
        val brain = brain(AskSource.APP)
        val answer = RoutedAsk(brain, states).answer(asking(AskTurn("a dragon film"), id = null))
        assertEquals(0, brain.routed)
        assertEquals(AskSource.WEB, answer.source)
        assertEquals(listOf("My Channel"), brain.webCalls.single().taste.channels)
    }

    @Test
    fun `a question about the app reads the snapshot and never reaches the web`() = runBlocking {
        states.put("snapshot-0001", envelope)
        val brain = brain(AskSource.APP)
        val answer = RoutedAsk(brain, states).answer(asking(AskTurn("how many unplayed?")))
        assertTrue(brain.webCalls.isEmpty())
        assertEquals(AskSource.APP, answer.source)
        assertEquals("you have 3", answer.reply)
        val card = answer.suggestions.single()
        assertEquals(AskKind.APP, card.kind)
        assertEquals(listOf("v1", "v2"), card.itemIds)
        assertEquals(4, answer.trace?.jqCalls)
        assertEquals(1, answer.trace?.jqDenied)
        assertEquals("app", answer.trace?.route)
    }

    @Test
    fun `a web question never sees the snapshot and keeps its taste`() = runBlocking {
        states.put("snapshot-0001", envelope)
        val brain = brain(AskSource.WEB)
        val answer = RoutedAsk(brain, states).answer(asking(AskTurn("a funny film")))
        assertTrue(brain.appCalls.isEmpty())
        assertEquals(AskSource.WEB, answer.source)
        assertEquals(listOf("My Channel"), brain.webCalls.single().taste.channels)
    }

    @Test
    fun `two independent questions are answered by two separate calls that share nothing`() = runBlocking {
        states.put("snapshot-0001", envelope)
        val brain = brain(AskSource.BOTH, appQ = "how many unplayed?", webQ = "news on the Madrid Open")
        val earlier =
            AskTurn("what is in my queue", listOf("Secret Title"), "Your queue holds Secret Title", AskSource.APP)
        val answer = RoutedAsk(brain, states).answer(asking(earlier, AskTurn("both please")))
        assertEquals(AskSource.BOTH, answer.source)
        assertTrue(answer.reply.startsWith("🗂️ you have 3"))
        assertTrue(answer.reply.contains("🌐 web says zog"))
        val web = brain.webCalls.single()
        assertEquals("news on the Madrid Open", web.turns.last().request)
        assertTrue("the web call carries nothing from the app", web.taste == Taste() && web.have.isEmpty())
        val earlierForWeb = web.turns.first()
        assertEquals("what is in my queue", earlierForWeb.request)
        assertEquals("", earlierForWeb.reply)
        assertTrue(earlierForWeb.suggested.isEmpty())
        assertEquals("how many unplayed?", brain.appCalls.single().first)
        assertEquals(listOf(AskKind.APP, AskKind.FILM), answer.suggestions.map { it.kind })
    }

    @Test
    fun `a web part that needs the app's data is not answered and the reply says why`() = runBlocking {
        states.put("snapshot-0001", envelope)
        val brain = brain(AskSource.BOTH, appQ = "what did I play last?", webQ = "a film like it", needs = true)
        val answer = RoutedAsk(brain, states).answer(asking(AskTurn("find a film like the last thing I played")))
        assertTrue(brain.webCalls.isEmpty())
        assertEquals(AskSource.APP, answer.source)
        assertTrue(answer.reply.contains(AskRouting.DEPENDENT_NOTE))
        assertEquals("what did I play last?", brain.appCalls.single().first)
    }

    @Test
    fun `one failing half still returns the other and says what failed`() = runBlocking {
        states.put("snapshot-0001", envelope)
        val brain = brain(AskSource.BOTH, appQ = "a", webQ = "b").also { it.appFails = true }
        val answer = RoutedAsk(brain, states).answer(asking(AskTurn("both")))
        assertTrue(answer.reply.contains("⚠️ The part about your app failed: jq fell over"))
        assertTrue(answer.reply.contains("🌐 web says zog"))
    }

    @Test
    fun `an app question with an expired snapshot fails loudly instead of guessing`() = runBlocking {
        val brain = brain(AskSource.APP)
        try {
            RoutedAsk(brain, states).answer(asking(AskTurn("how many?"), id = "gone-snapshot"))
            fail("expected a failure")
        } catch (e: BrainFailure) {
            assertTrue(e.message!!.contains("expired"))
        }
        assertTrue(brain.appCalls.isEmpty())
    }

    @Test
    fun `both with the snapshot gone answers the web part and says the app part was skipped`() = runBlocking {
        val brain = brain(AskSource.BOTH, appQ = "a", webQ = "b")
        val answer = RoutedAsk(brain, states).answer(asking(AskTurn("both"), id = "gone-snapshot"))
        assertEquals(AskSource.WEB, answer.source)
        assertTrue(answer.reply.contains(AskRouting.NO_SNAPSHOT_NOTE))
        assertFalse(brain.webCalls.isEmpty())
    }

    @Test
    fun `earlier app answers are withheld from the web and earlier web answers from the app`() {
        val turns = listOf(
            AskTurn("q1", listOf("S"), "r1", AskSource.APP),
            AskTurn("q2", listOf("T"), "r2", AskSource.WEB),
            AskTurn("q3", listOf("U"), "r3", AskSource.BOTH),
        )
        val web = AskRouting.forWeb(turns)
        assertEquals(listOf("", "r2", ""), web.map { it.reply })
        assertTrue(web[0].suggested.isEmpty() && web[2].suggested.isEmpty())
        val app = AskRouting.forApp(turns)
        assertEquals(listOf("r1", "", ""), app.map { it.reply })
    }

    @Test
    fun `plans follow the router's decision and its two safety rules`() {
        fun plan(d: RouteDecision, hasState: Boolean = true) = AskRouting.plan(d, hasState, "latest")
        assertEquals(AskPlan.Web, plan(RouteDecision(AskSource.WEB, "", "", false, "")))
        assertEquals(AskPlan.App("latest"), plan(RouteDecision(AskSource.APP, "x", "y", false, "")))
        assertEquals(
            AskPlan.Both("a", "b"),
            plan(RouteDecision(AskSource.BOTH, "a", "b", false, "")),
        )
        assertEquals(
            AskPlan.App("a", AskRouting.DEPENDENT_NOTE),
            plan(RouteDecision(AskSource.BOTH, "a", "b", true, "")),
        )
        assertEquals(AskPlan.Web, plan(RouteDecision(AskSource.BOTH, "a", "b", false, ""), hasState = false))
        assertEquals(AskPlan.Web, plan(RouteDecision(AskSource.BOTH, "", "b", false, "")))
        assertEquals(AskPlan.App("a"), plan(RouteDecision(AskSource.BOTH, "a", "", false, "")))
        assertNull((plan(RouteDecision(AskSource.APP, "", "", false, "")) as AskPlan.App).note)
    }

    @Test
    fun `cards keep only ids the snapshot holds, at most thirty a card and five cards`() {
        val many = (1..40).map { "id$it" }
        val reply = AppReply(
            "r",
            listOf(
                AppCard("big", "n", many + "ghost"),
                AppCard("none", "n", listOf("ghost")),
            ) + (1..6).map { AppCard("c$it", "n", listOf("id1")) },
            0,
            0,
        )
        val (cards, dropped) = AskRouting.cardsOf(reply, many.toSet())
        assertEquals(5, cards.size)
        assertEquals(30, cards.first().itemIds.size)
        assertTrue(dropped >= 11)
        assertFalse(cards.any { it.title == "none" })
    }
}
