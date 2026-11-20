package com.dewijones92.totum.data.phone.google

import com.dewijones92.totum.data.phone.ImportedKeys
import com.dewijones92.totum.data.phone.ImportedPhoneMedia
import com.dewijones92.totum.data.phone.PhoneMediaQuery
import com.dewijones92.totum.domain.PhoneMediaKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class GooglePhotosPickerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val server = MockWebServer()
    private val calls = mutableListOf<String>()
    private var sessionStatus = 200
    private var polls = 0
    private var pollsUntilSet = 1
    private var expireTime = "2099-01-01T00:00:00Z"
    private var failVideoDownload = false

    @Before
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                calls += "${request.method} $path"
                return when {
                    request.method == "POST" && path == "/v1/sessions" ->
                        if (sessionStatus == 200) {
                            json(
                                """{"id":"sess-1","pickerUri":"https://photos.google.com/picker/abc",""" +
                                    """"pollingConfig":{"pollInterval":"2s","timeoutIn":"600s"},""" +
                                    """"expireTime":"$expireTime"}""",
                            )
                        } else {
                            MockResponse.Builder().code(sessionStatus)
                                .body("""{"error":{"message":"API not enabled"}}""").build()
                        }
                    request.method == "GET" && path == "/v1/sessions/sess-1" -> {
                        polls++
                        json("""{"id":"sess-1","mediaItemsSet":${polls >= pollsUntilSet},"expireTime":"$expireTime"}""")
                    }
                    request.method == "DELETE" -> json("{}")
                    path == "/v1/mediaItems" -> json(
                        """{"mediaItems":[
                          {"id":"photo-1","type":"PHOTO","createTime":"2024-05-01T10:00:00Z",
                           "mediaFile":{"baseUrl":"${server.url("/media/photo-1")}","mimeType":"image/jpeg","filename":"Beach.jpg",
                           "mediaFileMetadata":{"width":4000,"height":3000}}},
                          {"id":"video-1","type":"VIDEO","mediaFile":{"baseUrl":"${server.url("/media/video-1")}",
                           "mimeType":"video/mp4","filename":"Clip.mp4"}}]}""",
                    )
                    path == "/media/photo-1=d" || path == "/media/photo-1" ->
                        MockResponse.Builder().code(200).body("jpeg-bytes").build()
                    path == "/media/video-1=dv" || path == "/media/video-1" ->
                        if (failVideoDownload) {
                            MockResponse.Builder().code(500).build()
                        } else {
                            MockResponse.Builder().code(200).body("mp4-bytes").build()
                        }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun json(body: String) = MockResponse.Builder().code(200).body(body).build()

    private val config = GooglePhotosConfig("id", "secret")
    private val opened = mutableListOf<String>()
    private var signInResult: GoogleSignIn = GoogleSignIn.Done
    private var signInCalls = 0

    private fun picker(
        config: GooglePhotosConfig = this.config,
        tokens: GoogleTokenStore = InMemoryGoogleTokenStore(GoogleTokens("tok", "ref", Long.MAX_VALUE / 2)),
        imports: ImportedPhoneMedia = ImportedPhoneMedia(File(folder.root, "imports")),
        clock: () -> Long = { 0L },
        pause: suspend (Long) -> Unit = {},
    ): GooglePhotosPicker {
        val oauth = GoogleOAuthClient(OkHttpClient(), config, tokens, clock, tokenUrl = server.url("/token").toString())
        val api = PhotosPickerApi(
            OkHttpClient(),
            token = { oauth.accessToken() },
            refreshToken = { oauth.forceRefresh() },
            base = server.url("/v1").toString().trimEnd('/'),
        )
        return GooglePhotosPicker(
            config,
            oauth,
            GoogleSignInPort {
                signInCalls++
                if (signInResult == GoogleSignIn.Done) tokens.save(GoogleTokens("tok", "ref", Long.MAX_VALUE / 2))
                signInResult
            },
            api,
            imports,
            BrowserOpener {
                opened += it
                true
            },
            clock,
            pause,
        )
    }

    @Test
    fun `with no Google client the picker says it needs setup and calls nothing`() = runTest {
        val picker = picker(config = GooglePhotosConfig.NOT_SET_UP)

        assertEquals(PickerState.NeedsSetup, picker.state.value)
        assertEquals(PickerState.NeedsSetup, picker.pick())
        assertTrue(calls.isEmpty())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `set up but signed out the picker starts signed out, ready once there is a token`() = runTest {
        assertEquals(PickerState.SignedOut, picker(tokens = InMemoryGoogleTokenStore()).state.value)
        assertEquals(PickerState.Ready, picker().state.value)
    }

    @Test
    fun `a pick signs in first when there is no token, then carries on`() = runTest {
        val picker = picker(tokens = InMemoryGoogleTokenStore())

        val end = picker.pick()

        assertEquals(1, signInCalls)
        assertTrue(end is PickerState.Finished)
    }

    @Test
    fun `a failed sign-in ends the pick with its reason and creates no session`() = runTest {
        signInResult = GoogleSignIn.Failed("❌ Google said no: access_denied")
        val picker = picker(tokens = InMemoryGoogleTokenStore())

        val end = picker.pick()

        assertEquals(PickerState.Failed("❌ Google said no: access_denied"), end)
        assertFalse(calls.any { it.startsWith("POST /v1/sessions") })
    }

    @Test
    fun `a pick opens Google's page, waits for the choice, then imports every picked item`() = runTest {
        pollsUntilSet = 3
        val imports = ImportedPhoneMedia(File(folder.root, "imports"))
        val reports = mutableListOf<Triple<Int?, Int?, Int?>>()

        val end = picker(imports = imports).pick { found, step, steps -> reports += Triple(found, step, steps) }

        assertEquals(PickerState.Finished(imported = 2, alreadyHad = 0, failed = emptyList()), end)
        assertEquals(listOf("https://photos.google.com/picker/abc"), opened)
        assertEquals(3, polls)
        assertTrue(reports.any { it.second == 1 && it.third == 2 })
        val photo = imports.page(PhoneMediaQuery(PhoneMediaKind.PHOTO)).entries.single()
        assertEquals("Beach", photo.title)
        assertEquals("jpeg-bytes", File(photo.path).readText())
        assertEquals(4000, photo.width)
        val video = imports.page(PhoneMediaQuery(PhoneMediaKind.VIDEO)).entries.single()
        assertEquals("mp4-bytes", File(video.path).readText())
        assertTrue(photo.path.endsWith(".jpg") && video.path.endsWith(".mp4"))
        assertTrue(calls.contains("DELETE /v1/sessions/sess-1"))
    }

    @Test
    fun `a photo is downloaded with the d suffix and a video with dv, both with the bearer token`() = runTest {
        picker().pick()

        val paths = calls.filter { it.startsWith("GET /media") }
        assertEquals(listOf("GET /media/photo-1=d", "GET /media/video-1=dv"), paths)
    }

    @Test
    fun `an item picked before is not downloaded again`() = runTest {
        val imports = ImportedPhoneMedia(File(folder.root, "imports"))
        picker(imports = imports).pick()
        calls.clear()

        val end = picker(imports = imports).pick() as PickerState.Finished

        assertEquals(0, end.imported)
        assertEquals(2, end.alreadyHad)
        assertTrue(calls.none { it.startsWith("GET /media") })
    }

    @Test
    fun `one item that fails to download is named, and the rest still import`() = runTest {
        failVideoDownload = true

        val end = picker().pick() as PickerState.Finished

        assertEquals(1, end.imported)
        assertEquals(1, end.failed.size)
        assertTrue(end.failed.single().startsWith("Clip.mp4"))
    }

    @Test
    fun `a session Google forbids names the likely cause`() = runTest {
        sessionStatus = 403

        val end = picker().pick() as PickerState.Failed

        assertTrue(end.reason, end.reason.contains("Photos Picker API"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a session that is never picked from expires and imports nothing`() = runTest {
        pollsUntilSet = Int.MAX_VALUE
        var now = 0L
        val picker = picker(clock = { now }, pause = { now += it + 400_000 })

        val end = picker.pick()

        assertEquals(PickerState.Expired, end)
        assertTrue(calls.none { it.startsWith("GET /v1/mediaItems") })
        assertTrue(calls.contains("DELETE /v1/sessions/sess-1"))
    }

    @Test
    fun `nothing picked is a finished pick of nothing`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" -> json("""{"id":"s","pickerUri":"https://p/x"}""")
                request.url.encodedPath.endsWith("/mediaItems") -> json("{}")
                else -> json("""{"id":"s","mediaItemsSet":true}""")
            }
        }

        assertEquals(PickerState.Finished(0, 0, emptyList()), picker().pick())
    }

    @Test
    fun `cancelling while waiting ends the session and leaves the picker cancelled`() = runTest {
        pollsUntilSet = Int.MAX_VALUE
        val gate = CompletableDeferred<Unit>()
        val picker = picker(pause = { gate.await() })

        val job = launch { picker.pick() }
        picker.state.first { it is PickerState.WaitingForPick }
        job.cancel()
        job.join()

        assertEquals(PickerState.Cancelled, picker.state.value)
    }

    @Test
    fun `the keys of Google items are stable so a re-pick is recognised`() {
        assertEquals(ImportedKeys.keyOf("photo-1"), ImportedKeys.keyOf("photo-1"))
    }
}
