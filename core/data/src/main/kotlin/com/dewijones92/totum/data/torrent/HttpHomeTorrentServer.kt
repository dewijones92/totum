package com.dewijones92.totum.data.torrent

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.common.HttpUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.time.TimeSource

/**
 * [HomeTorrentServer] over Prowlarr (search) and TorrServer (streaming) on Dewi's Pi.
 *
 * Both sit behind the same nginx + oauth2-proxy gate, restricted to one Google address, so every
 * request here needs the session cookie the app obtained at sign-in — supplied by whatever
 * `CookieJar` the [client] carries, which is why authentication does not appear in this class at
 * all.
 *
 * Endpoints verified live 2026-08-01 against the real services rather than read from docs.
 */
public class HttpHomeTorrentServer(
    private val client: OkHttpClient,
    /** One host, e.g. `https://totum.example.com` — Prowlarr under `/prowlarr/`, TorrServer `/ts/`. */
    private val base: String,
    /**
     * Prowlarr requires its own key behind the proxy; the gate protects, it does not identify.
     *
     * Read per call, exactly like [token], and for a reason that cost a whole day. It used to be
     * captured by VALUE when this was constructed — so on a fresh install the client was built
     * with an empty key before sign-in, and the key the sign-in then delivered was never used.
     * Prowlarr answers a bad key with 401, which is indistinguishable from the gate refusing the
     * token, so signing in "worked" and every search still failed until the app was restarted.
     * Seen in report 0.1.313: `sign-in returned token=true prowlarrKey=true` at 15:53:04,
     * followed by HTTP 401 twelve seconds later.
     */
    private val prowlarrApiKey: () -> String,
    /**
     * The token obtained by signing in with Google, replayed on every request.
     *
     * Read per call rather than captured, so a fresh sign-in takes effect immediately instead of
     * after a restart — and so a blank one produces an honest 401 rather than a silent failure.
     */
    private val token: () -> String,
    private val lateDeadlineMs: Long = LATE_DEADLINE_MS,
) : HomeTorrentServer {

    private val prowlarr =
        ProwlarrSearch(client, "$base/prowlarr", prowlarrApiKey, token, lateDeadlineMs)

    private val torrServerBase get() = "$base/ts"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String): TorrentSearchResult = withContext(Dispatchers.IO) {
        // Asked BEFORE the request, because signing in is the one cause the person can fix and
        // the reply it produces is indistinguishable from every other refusal: nginx answers a
        // missing token and a wrong one with the same bare 401. Report 0.1.308 said only
        // "search failed: HTTP 401", which named the symptom and hid the entire cause.
        if (token().isBlank()) {
            Diag.log("torrent", "not searching for \"$query\": not signed in to the home server")
            return@withContext TorrentSearchResult.Failure("sign in to the home server first")
        }
        prowlarr.search(query)
    }

    override fun searchUpdates(query: String): Flow<TorrentSearchResult> =
        if (token().isBlank()) {
            flow {
                Diag.log("torrent", "not searching for \"$query\": not signed in to the home server")
                emit(TorrentSearchResult.Failure("sign in to the home server first"))
            }
        } else {
            prowlarr.updates(query).flowOn(Dispatchers.IO)
        }

    override suspend fun prepare(magnet: String): PreparedTorrent? = withContext(Dispatchers.IO) {
        val started = TimeSource.Monotonic.markNow()
        // save_to_db false: the server keeps a RAM cache and nothing is written to disk, which is
        // what makes this sustainable on a Pi that is 88% full.
        val added = post(
            "$torrServerBase/torrents",
            """{"action":"add","link":${magnet.quoted()},"save_to_db":false}""",
        ) ?: return@withContext null
        val hash = added["hash"]?.jsonPrimitive?.contentOrNull ?: run {
            Diag.warn("torrent", "server accepted the magnet but returned no hash")
            return@withContext null
        }
        val name = added["name"]?.jsonPrimitive?.contentOrNull ?: "torrent"
        // Metadata arrives from the swarm a moment after the add, so the file list is asked for
        // separately — and WAITED FOR. Asking once returns an empty list while the metadata is
        // still in flight: report 0.1.317 shows "prepared … with 0 file(s)" followed 14 seconds
        // later by the same torrent with 89, which to anyone tapping a search result is a
        // season that silently had nothing in it.
        val addedMs = started.elapsedNow().inWholeMilliseconds
        val files = awaitFiles(hash)
        Diag.log(
            "torrent",
            "prepared ${hash.take(HASH_CHARS)} \"$name\" with ${files.size} file(s) in " +
                "${started.elapsedNow().inWholeMilliseconds}ms (added in ${addedMs}ms)",
        )
        PreparedTorrent(hash, name, files)
    }

    /**
     * The file list once the swarm has supplied the metadata, or empty if it never does.
     *
     * Polled rather than pushed because the server offers no signal for it. Bounded so a magnet
     * with no seeders fails in seconds instead of hanging on a tap forever.
     */
    private suspend fun awaitFiles(hash: String): List<TorrentFile> {
        repeat(METADATA_ATTEMPTS) { attempt ->
            val files = filesFor(hash)
            if (files.isNotEmpty()) return files
            if (attempt == 0) Diag.log("torrent", "waiting for ${hash.take(HASH_CHARS)}'s metadata")
            delay(METADATA_POLL_MS)
        }
        Diag.warn("torrent", "${hash.take(HASH_CHARS)} never produced a file list")
        return emptyList()
    }

    private fun filesFor(hash: String): List<TorrentFile> {
        val listed = post("$torrServerBase/torrents", """{"action":"get","hash":${hash.quoted()}}""")
        val stats = listed?.get("file_stats") as? JsonArray ?: return emptyList()
        return stats.mapIndexedNotNull { position, element ->
            val file = element.jsonObject
            TorrentFile(
                index = file["id"]?.jsonPrimitive?.intOrNull ?: (position + 1),
                path = file["path"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null,
                sizeBytes = file["length"]?.jsonPrimitive?.longOrNull ?: 0,
            )
        }
    }

    /**
     * The token rides in the QUERY here, not a header, and that is deliberate.
     *
     * This URL is handed to ExoPlayer, which fetches it with its own HTTP stack and knows
     * nothing about the app's headers. A header-only scheme would authenticate every call the
     * app makes and then fail on the one that actually plays the video.
     */
    override fun stream(torrent: PreparedTorrent, file: TorrentFile): HttpUrl = HttpUrl.of(
        "$torrServerBase/stream/${file.name.urlPath()}" +
            "?link=${torrent.hash}&index=${file.index}&play&totumToken=${token()}",
    )

    /**
     * The audio-only HLS playlist for one file. Same token-in-the-query trick as [stream], and
     * for the same reason: ExoPlayer fetches this with its own HTTP stack and none of our
     * headers, and it fetches every SEGMENT the same way — so the token has to survive into the
     * playlist's relative URLs, which it does because they resolve against this address.
     */
    override fun audioStream(torrent: PreparedTorrent, file: TorrentFile): HttpUrl = HttpUrl.of(
        "$torrServerBase/audio/${torrent.hash}/${file.index}/index.m3u8?totumToken=${token()}",
    )

    override suspend fun warmAudio(audioUrl: HttpUrl) {
        // The playlist and its start trigger are siblings, so the URL an item already carries
        // names the job to start. Anything else is not one of ours and is left alone.
        val start = audioUrl.value.substringBefore('?').removeSuffix("/index.m3u8")
        if (!start.startsWith("$torrServerBase/audio/")) {
            Diag.log("torrent", "not warming ${audioUrl.value.take(URL_CHARS)}: not a home-server audio stream")
            return
        }
        val what = start.substringAfterLast("/audio/")
        withContext(Dispatchers.IO) {
            val url = "$start/start"
            val request = Request.Builder().url(url).header(TOKEN_HEADER, token()).get().build()
            // Fire and forget: the point is to start the ~25s of work, not to wait for it.
            runCatching { client.newCall(request).execute().use { it.code } }
                .onSuccess { Diag.log("torrent", "warming audio for $what") }
                .onFailure { Diag.warn("torrent", "could not warm audio for $what", it) }
        }
    }

    override suspend fun warmVideo(torrent: PreparedTorrent, file: TorrentFile): Unit = withContext(Dispatchers.IO) {
        val url = stream(torrent, file).value
        val what = "${torrent.hash.take(HASH_CHARS)}/${file.index}"
        coroutineScope {
            listOf("start" to "bytes=0-${WARM_BYTES - 1}", "end" to "bytes=-$WARM_BYTES").forEach { (part, range) ->
                launch {
                    val started = TimeSource.Monotonic.markNow()
                    val request = Request.Builder().url(url).header("Range", range).get().build()
                    runCatching { client.newCall(request).await().use { it.code to it.body.bytes().size } }
                        .onSuccess { (code, bytes) ->
                            Diag.log(
                                "torrent",
                                "warmed the $part of $what: HTTP $code, $bytes bytes in " +
                                    "${started.elapsedNow().inWholeMilliseconds}ms",
                            )
                        }
                        .onFailure { Diag.warn("torrent", "could not warm the $part of $what", it) }
                }
            }
        }
    }

    private fun post(url: String, body: String): JsonObject? = try {
        val request = Request.Builder()
            .url(url)
            .header(TOKEN_HEADER, token())
            .post(body.toRequestBody(JSON_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            when {
                !response.isSuccessful -> {
                    Diag.warn("torrent", "POST $url failed: HTTP ${response.code}")
                    null
                }
                text.isBlank() -> null
                else -> json.parseToJsonElement(text) as? JsonObject
            }
        }
    } catch (e: IOException) {
        Diag.warn("torrent", "POST $url could not reach the home server", e)
        null
    }

    /** Minimal JSON string quoting — magnets carry `&`, `=` and quotes that would break a body. */

    internal companion object {
        val JSON_TYPE = "application/json".toMediaType()

        /** Checked by nginx before anything is proxied; a wrong or missing one is a 401. */
        const val TOKEN_HEADER = "X-Totum-Token"

        const val HASH_CHARS = 12
        const val URL_CHARS = 80

        /** Roughly 30s of waiting for a swarm to answer, which is generous and still bounded. */
        const val METADATA_ATTEMPTS = 30
        const val METADATA_POLL_MS = 1_000L

        /** Both mean "sign in again" / "that was slow" rather than "the server is broken". */
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_GATEWAY_TIMEOUT = 504

        const val LATE_DEADLINE_MS = 120_000L
        const val WARM_BYTES = 1_048_576
        const val SLOW_INDEXERS = "the home server's indexers are slow to answer — try again in a minute"
    }
}

private fun String.quoted(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Only what a path segment cannot contain; the server treats this purely as a label. */
private fun String.urlPath(): String = replace(" ", "%20").replace("?", "").replace("#", "")
