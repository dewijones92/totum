package com.dewijones92.totum.data.phone.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

class GoogleOAuthTest {

    private val server = MockWebServer()

    @Before
    fun start() = server.start()

    @After
    fun stop() = server.close()

    private val config = GooglePhotosConfig("client-id.apps.googleusercontent.com", "client-secret")

    private fun client(store: GoogleTokenStore, now: () -> Long = { 1_000_000L }) =
        GoogleOAuthClient(OkHttpClient(), config, store, now, tokenUrl = server.url("/token").toString())

    private fun granted(access: String, refresh: String? = null, expires: Long = 3600) =
        MockResponse.Builder().code(200).body(
            """{"access_token":"$access"${refresh?.let { ""","refresh_token":"$it"""" } ?: ""},""" +
                """"expires_in":$expires}""",
        ).build()

    @Test
    fun `a config with no client id is not set up`() {
        assertFalse(GooglePhotosConfig.NOT_SET_UP.isSetUp)
        assertTrue(config.isSetUp)
    }

    @Test
    fun `the PKCE challenge is the base64url SHA-256 of the verifier and differs each time`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        )
        assertNotEquals(Pkce.newVerifier(), Pkce.newVerifier())
        assertTrue(Pkce.newVerifier().length in 43..128)
    }

    @Test
    fun `the sign-in address asks for only the picker scope, offline, with PKCE and the state`() {
        val url = GoogleAuthRequest.url(config, "http://127.0.0.1:5555/", "state-1", "challenge-1")
        val query = url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertEquals("https://www.googleapis.com/auth/photospicker.mediaitems.readonly", query["scope"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals("challenge-1", query["code_challenge"])
        assertEquals("state-1", query["state"])
        assertEquals("http://127.0.0.1:5555/", query["redirect_uri"])
        assertEquals("offline", query["access_type"])
        assertEquals("code", query["response_type"])
        assertEquals(config.clientId, query["client_id"])
    }

    @Test
    fun `exchanging a code stores the tokens and sends the verifier and redirect`() = runTest {
        server.enqueue(granted("access-1", "refresh-1"))
        val store = InMemoryGoogleTokenStore()

        val result = client(store).exchange("the-code", "the-verifier", "http://127.0.0.1:1/")

        assertEquals(GoogleSignIn.Done, result)
        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("code=the-code"))
        assertTrue(body.contains("code_verifier=the-verifier"))
        assertTrue(body.contains("grant_type=authorization_code"))
        assertEquals("access-1", store.load()?.accessToken)
        assertEquals("refresh-1", store.load()?.refreshToken)
        assertEquals(1_000_000L + 3_600_000L, store.load()?.expiresAtMs)
    }

    @Test
    fun `a refused code says why and stores nothing`() = runTest {
        server.enqueue(
            MockResponse.Builder().code(
                400
            ).body("""{"error":"invalid_grant","error_description":"Bad code"}""").build(),
        )
        val store = InMemoryGoogleTokenStore()

        val result = client(store).exchange("bad", "v", "http://127.0.0.1:1/")

        assertTrue((result as GoogleSignIn.Failed).reason.contains("Bad code"))
        assertNull(store.load())
    }

    @Test
    fun `a token that is still good is used without asking Google`() = runTest {
        val store = InMemoryGoogleTokenStore(GoogleTokens("still-good", "r", expiresAtMs = 1_000_000L + 600_000L))

        assertEquals("still-good", client(store).accessToken())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expired token is refreshed, and the refresh token is kept when Google sends none back`() = runTest {
        server.enqueue(granted("access-2"))
        val store = InMemoryGoogleTokenStore(GoogleTokens("old", "refresh-1", expiresAtMs = 1_000_000L + 10_000L))

        val token = client(store).accessToken()

        assertEquals("access-2", token)
        assertEquals("refresh-1", store.load()?.refreshToken)
        assertTrue(server.takeRequest().body!!.utf8().contains("grant_type=refresh_token"))
    }

    @Test
    fun `a refresh Google rejects as invalid_grant signs out for good`() = runTest {
        server.enqueue(MockResponse.Builder().code(400).body("""{"error":"invalid_grant"}""").build())
        val store = InMemoryGoogleTokenStore(GoogleTokens("old", "r", expiresAtMs = 0))
        val client = client(store)

        assertNull(client.accessToken())
        assertFalse(client.signedIn())
    }

    @Test
    fun `a refresh that fails on the network keeps the sign-in for the next try`() = runTest {
        server.close()
        val store = InMemoryGoogleTokenStore(GoogleTokens("old", "r", expiresAtMs = 0))
        val client = GoogleOAuthClient(
            OkHttpClient(),
            config,
            store,
            { 1_000_000L },
            tokenUrl = "http://127.0.0.1:1/token"
        )

        assertNull(client.accessToken())
        assertTrue(client.signedIn())
    }

    @Test
    fun `an expired token with no refresh token is signed out`() = runTest {
        val store = InMemoryGoogleTokenStore(GoogleTokens("old", null, expiresAtMs = 0))

        assertNull(client(store).accessToken())
        assertNull(store.load())
    }

    @Test
    fun `tokens never print themselves`() {
        assertFalse(GoogleTokens("secret-access", "secret-refresh", 1).toString().contains("secret"))
    }

    @Test
    fun `the loopback redirect hands back the code when the state matches`() = runBlocking {
        LoopbackRedirect("expected").use { redirect ->
            val port = redirect.redirectUri.substringAfterLast(':').trimEnd('/').toInt()
            val waiting = async(Dispatchers.IO) { redirect.await(5_000) }
            val connection = URL(
                "http://127.0.0.1:$port/?state=expected&code=4%2Fabc"
            ).openConnection() as HttpURLConnection
            val page = connection.inputStream.bufferedReader().readText()

            assertEquals(AuthRedirect.Code("4/abc"), waiting.await())
            assertTrue(page.contains("Signed in"))
        }
    }

    @Test
    fun `the loopback redirect refuses a wrong state, and reports a refusal by the user`() = runBlocking {
        LoopbackRedirect("expected").use { redirect ->
            val port = redirect.redirectUri.substringAfterLast(':').trimEnd('/').toInt()
            val waiting = async(Dispatchers.IO) { redirect.await(5_000) }
            (
                URL(
                    "http://127.0.0.1:$port/?state=forged&code=x"
                ).openConnection() as HttpURLConnection
                ).inputStream.close()

            assertEquals(AuthRedirect.WrongState, waiting.await())
        }
        LoopbackRedirect("expected").use { redirect ->
            val port = redirect.redirectUri.substringAfterLast(':').trimEnd('/').toInt()
            val waiting = async(Dispatchers.IO) { redirect.await(5_000) }
            (URL("http://127.0.0.1:$port/?state=expected&error=access_denied").openConnection() as HttpURLConnection)
                .inputStream.close()

            assertEquals(AuthRedirect.Refused("access_denied"), waiting.await())
        }
    }

    @Test
    fun `the loopback redirect gives up when nobody comes back`() = runBlocking {
        LoopbackRedirect("s").use { assertEquals(AuthRedirect.TimedOut, it.await(200)) }
    }
}
