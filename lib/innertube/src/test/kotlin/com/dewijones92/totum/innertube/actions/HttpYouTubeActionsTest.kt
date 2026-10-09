package com.dewijones92.totum.innertube.actions

import com.dewijones92.totum.innertube.auth.AccessToken
import com.dewijones92.totum.innertube.auth.OAuthTokens
import com.dewijones92.totum.innertube.auth.RefreshToken
import com.dewijones92.totum.innertube.auth.YouTubeAccount
import com.dewijones92.totum.innertube.auth.fake.FakeYouTubeAuth
import com.dewijones92.totum.innertube.auth.fake.InMemoryTokenStore
import com.dewijones92.totum.innertube.browse.InnerTubeClient
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HttpYouTubeActionsTest {

    private val server = MockWebServer()

    @Before fun setUp() = server.start()

    @After fun tearDown() = server.close()

    private fun actions(signedIn: Boolean = true): HttpYouTubeActions {
        val initial = if (signedIn) TOKENS else null
        val account = YouTubeAccount(FakeYouTubeAuth(), InMemoryTokenStore(initial), nowEpochSeconds = { 0 })
        val mock = server.url("/act").toString()
        return HttpYouTubeActions(
            account = account,
            innerTube = InnerTubeClient(OkHttpClient()),
            endpoints = HttpYouTubeActions.ActionEndpoints(
                subscribe = mock,
                unsubscribe = mock,
                like = mock,
                dislike = server.url("/act/dislike").toString(),
                removeLike = mock,
                createComment = mock,
                editPlaylist = mock,
                next = server.url("/next").toString(),
            ),
        )
    }

    private fun ok() = server.enqueue(
        MockResponse.Builder().code(200).body("""{"actionResult":{"status":"STATUS_SUCCEEDED"}}""").build(),
    )

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/actions/$name")) { "fixture $name missing" }
            .bufferedReader().use { it.readText() }

    private fun answer(body: String) = server.enqueue(MockResponse.Builder().code(200).body(body).build())

    @Test
    fun `a song already in Liked Music reads as liked, asked as the account`() = runBlocking {
        answer(fixture("next_tv_liked.json"))

        assertEquals(VideoRating.LIKE, actions().rating("BNMKGYiJpvg"))
        val request = server.takeRequest()
        assertEquals("Bearer at", request.headers["Authorization"])
        assertTrue(request.target.endsWith("/next"))
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains(""""videoId":"BNMKGYiJpvg""""))
        assertTrue(body.contains("TVHTML5"))
    }

    @Test
    fun `a song not rated reads as not rated`() = runBlocking {
        answer(fixture("next_tv_not_rated.json"))

        assertEquals(VideoRating.NONE, actions().rating("BNMKGYiJpvg"))
    }

    @Test
    fun `a dislike reads as disliked, from the entity when the button is absent`() = runBlocking {
        answer(
            """{"frameworkUpdates":{"entityBatchUpdate":{"mutations":""" +
                """[{"payload":{"likeStatusEntity":{"likeStatus":"DISLIKE"}}}]}}}""",
        )

        assertEquals(VideoRating.DISLIKE, actions().rating("BNMKGYiJpvg"))
    }

    @Test
    fun `nothing is known when signed out or when the answer says nothing`() = runBlocking {
        assertEquals(null, actions(signedIn = false).rating("BNMKGYiJpvg"))
        answer("{}")
        assertEquals(null, actions().rating("BNMKGYiJpvg"))
    }

    @Test
    fun `like posts the video target with a bearer token`() = runBlocking {
        ok()
        assertEquals(ActionResult.Success, actions().setRating("vid12345678", VideoRating.LIKE))
        val request = server.takeRequest()
        assertEquals("Bearer at", request.headers["Authorization"])
        val body = request.body?.utf8().orEmpty()
        assertTrue(body.contains(""""videoId":"vid12345678""""))
        assertTrue(body.contains("TVHTML5"))
    }

    @Test
    fun `dislike posts to the dislike endpoint`() = runBlocking {
        ok()
        assertEquals(ActionResult.Success, actions().setRating("vid12345678", VideoRating.DISLIKE))
        assertTrue(server.takeRequest().target.contains("/act/dislike"))
    }

    @Test
    fun `save to Watch Later adds the video to the WL playlist`() = runBlocking {
        ok()
        assertEquals(ActionResult.Success, actions().setSavedToWatchLater("vid12345678", saved = true))
        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body.contains(""""playlistId":"WL""""))
        assertTrue(body.contains("ACTION_ADD_VIDEO"))
        assertTrue(body.contains(""""addedVideoId":"vid12345678""""))
    }

    @Test
    fun `subscribe and unsubscribe carry the channel id`() = runBlocking {
        ok()
        assertEquals(ActionResult.Success, actions().setSubscribed("UCabc", subscribed = true))
        assertTrue(server.takeRequest().body?.utf8().orEmpty().contains(""""channelIds":["UCabc"]"""))
    }

    @Test
    fun `posting a comment sends built params and escaped text`() = runBlocking {
        ok()
        assertEquals(ActionResult.Success, actions().postComment("vid12345678", """he said "hi"\ok"""))
        val body = server.takeRequest().body?.utf8().orEmpty()
        assertTrue(body.contains("createCommentParams"))
        // Quotes and backslash in the text are escaped, so the JSON stays valid.
        assertTrue(body.contains("""he said \"hi\""""))
    }

    @Test
    fun `signed out short-circuits without a request`() = runBlocking {
        assertEquals(ActionResult.SignedOut, actions(signedIn = false).setRating("v", VideoRating.LIKE))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a server error is a failure`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(500).body("").build())
        assertTrue(actions().setSubscribed("UCabc", subscribed = false) is ActionResult.Failure)
    }

    private companion object {
        val TOKENS = OAuthTokens(AccessToken("at"), RefreshToken("rt"), expiresAtEpochSeconds = 3_600)
    }

    /**
     * The bug proven end to end on 2026-07-29: adding to Watch Later logged Success and the video
     * was not there afterwards. InnerTube refuses inside a **200**, so treating any 2xx as success
     * reported work that never happened.
     */
    @Test
    fun `a refusal inside a 200 is a failure, not a success`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"actionResult":{"status":"STATUS_FAILED"}}""")
                .build(),
        )

        val result = actions().setSavedToWatchLater("abc123", saved = true)

        assertTrue("expected a failure, got $result", result is ActionResult.Failure)
    }

    @Test
    fun `an error object inside a 200 is a failure and carries the reason`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"error":{"code":400,"message":"Precondition check failed."}}""")
                .build(),
        )

        val result = actions().setSavedToWatchLater("abc123", saved = true)

        assertEquals(ActionResult.Failure("Precondition check failed."), result)
    }

    /**
     * Conservative on purpose: not every action endpoint reports a status, so a body without one
     * still counts as success. Demanding it would break like and subscribe for no evidence.
     */
    @Test
    fun `a body with no status is still a success`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"responseContext":{}}""").build())

        assertEquals(ActionResult.Success, actions().setSavedToWatchLater("abc123", saved = true))
    }
}
