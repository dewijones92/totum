package com.dewijones92.totum.cli.serve.jellyfin

import com.dewijones92.totum.cli.serve.AskServer
import com.dewijones92.totum.cli.serve.Brain
import com.dewijones92.totum.cli.serve.filing.Jellyfin
import com.dewijones92.totum.data.ask.AskAnswer
import com.dewijones92.totum.data.ask.AskRequest
import com.dewijones92.totum.data.ask.VetAnswer
import com.dewijones92.totum.data.ask.VetRequest
import com.dewijones92.totum.data.jellyfin.JellyfinKind
import com.dewijones92.totum.data.jellyfin.JellyfinListing
import com.dewijones92.totum.data.targets.TargetsJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class JellyfinRoutesTest {

    private val asked = mutableListOf<String>()
    private val authorizations = mutableListOf<String?>()
    private val ranges = mutableListOf<String?>()

    private val series = """
        {"Items":[
          {"Id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","Name":"Bluey","Type":"Series","ProductionYear":2018,
           "Overview":"Dogs."},
          {"Id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","Name":"Heat","Type":"Movie","ProductionYear":1995,
           "RunTimeTicks":102000000000,"Container":"mp4"},
          {"Id":"cccccccccccccccccccccccccccccccc","Name":"Extras","Type":"Folder"},
          {"Name":"no id","Type":"Movie"}
        ],"TotalRecordCount":4}
    """.trimIndent()

    private val bytes = ByteArray(100) { it.toByte() }

    private val fakeJellyfin = Interceptor { chain ->
        val request = chain.request()
        asked += request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        authorizations += request.header("Authorization")
        ranges += request.header("Range")
        val ranged = request.header("Range") != null
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (request.url.encodedPath.startsWith("/Videos/")) {
            builder.code(if (ranged) 206 else 200).message("ok")
                .header("Content-Type", "video/x-matroska")
                .header("Accept-Ranges", "bytes")
                .apply { if (ranged) header("Content-Range", "bytes 10-19/100") }
                .body(
                    (if (ranged) bytes.copyOfRange(10, 20) else bytes).toResponseBody("video/x-matroska".toMediaType())
                )
        } else {
            builder.code(200).message("ok").body(series.toResponseBody("application/json".toMediaType()))
        }
        builder.build()
    }

    private val jellyfin = Jellyfin(
        OkHttpClient.Builder().addInterceptor(fakeJellyfin).build(),
        "http://jellyfin.invalid:8096",
        "secret-key",
        emptyMap(),
    )

    private val noBrain = object : Brain {
        override suspend fun suggest(request: AskRequest) = AskAnswer(emptyList())
        override suspend fun vet(request: VetRequest) = VetAnswer(emptyList())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = AskServer(noBrain, scope, null, listOf(JellyfinRoutes(JellyfinBrowse(jellyfin)))).start(0)
    private val base = "http://localhost:${server.address.port}"
    private val http = HttpClient.newHttpClient()

    @After
    fun stop() {
        server.stop(0)
        scope.cancel()
    }

    private fun get(path: String, range: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(base + path)).GET()
        range?.let { builder.header("Range", it) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `the top level lists films and series, read from Jellyfin with the Pi's key and no key in the answer`() {
        val answer = get("/jellyfin/browse")

        val listing = TargetsJson.decodeFromString(JellyfinListing.serializer(), String(answer.body()))
        assertEquals(200, answer.statusCode())
        assertEquals(listOf("Bluey", "Heat", "Extras"), listing.items.map { it.name })
        assertEquals(
            listOf(JellyfinKind.SERIES, JellyfinKind.MOVIE, JellyfinKind.OTHER),
            listing.items.map { it.kind },
        )
        assertEquals(102_000_000_000L / 10_000, listing.items[1].runtimeMs)
        assertEquals("MediaBrowser Token=\"secret-key\"", authorizations.single())
        assertFalse(String(answer.body()).contains("secret-key"))
        assertTrue(asked.single().startsWith("/Items?Recursive=true&IncludeItemTypes=Movie,Series"))
    }

    @Test
    fun `a parent is browsed by its children, and only a real id is accepted`() {
        get("/jellyfin/browse?parent=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")

        assertTrue(asked.single().startsWith("/Items?ParentId=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&SortBy="))

        val refused = get("/jellyfin/browse?parent=x%26api_key%3Dother")

        assertEquals(400, refused.statusCode())
        assertEquals(1, asked.size)
    }

    @Test
    fun `a stream is Jellyfin's own file, with the player's range passed through and sent back`() {
        val whole = get("/jellyfin/stream/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.mp4")
        val part = get("/jellyfin/stream/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.mp4", range = "bytes=10-19")

        assertEquals(200, whole.statusCode())
        assertEquals(100, whole.body().size)
        assertEquals(206, part.statusCode())
        assertEquals(bytes.copyOfRange(10, 20).toList(), part.body().toList())
        assertEquals("bytes 10-19/100", part.headers().firstValue("Content-Range").get())
        assertEquals(listOf(null, "bytes=10-19"), ranges)
        assertTrue(asked.all { it.startsWith("/Videos/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/stream?static=true") })
    }

    @Test
    fun `a stream path that is not an item is refused before Jellyfin is asked`() {
        val refused = get("/jellyfin/stream/..%2F..%2Fetc.mkv")

        assertEquals(400, refused.statusCode())
        assertTrue(asked.isEmpty())
    }
}
