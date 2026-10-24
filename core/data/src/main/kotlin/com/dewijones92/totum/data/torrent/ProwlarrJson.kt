package com.dewijones92.totum.data.torrent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Reads Prowlarr's search response.
 *
 * Split from the HTTP client for the same reason `BridgeJson` is split from the yt-dlp engine:
 * parsing is where the surprises are, and it can be tested against a captured real response with
 * no network, no Pi and no swarm.
 */
/**
 * Null when the body is not a Prowlarr search response at all.
 *
 * Total rather than throwing, because the thing on the other end is not guaranteed to be
 * Prowlarr. Both services behind the home gate answer an unrecognised path with their own web UI
 * and **HTTP 200**, so a misrouted request arrives here as a page of HTML that parses as neither
 * JSON nor anything else — and on 2026-08-01 that crashed the app outright, mid-search, on an
 * emulator. A search that cannot be read is a failed search, never a dead process.
 */
public fun parseProwlarr(body: String): List<TorrentResult>? =
    (runCatching { LENIENT.parseToJsonElement(body) }.getOrNull() as? JsonArray)
        ?.mapNotNull { runCatching { it.jsonObject.toResult() }.getOrNull() }

/**
 * Picks the field that IS a magnet, rather than the one named like one.
 *
 * Verified against the live service 2026-08-01, and it is a trap worth naming: `magnetUrl` is NOT
 * a magnet — it is a Prowlarr download-proxy URL carrying the API key
 * (`http://prowlarr/1/download?apikey=…`) — while `guid` holds the actual `magnet:?xt=urn:btih:…`.
 * Preferring the obvious-sounding field hands the streaming server an HTTP address instead of a
 * magnet, and the failure looks like a broken server rather than a parsing mistake.
 *
 * So candidates are judged by their VALUE. A result with no magnet anywhere is dropped: a
 * `.torrent` behind an authenticated URL is not something the streaming server can fetch, so half
 * a result is worse than none.
 */
private fun JsonObject.toResult(): TorrentResult? {
    val title = this["title"]?.jsonPrimitive?.contentOrNull ?: return null
    val link = MAGNET_FIELDS
        .mapNotNull { field -> this[field]?.jsonPrimitive?.contentOrNull }
        .firstOrNull { it.startsWith("magnet:", ignoreCase = true) }
        ?.let(TorrentLink::Magnet)
        ?: infoHash()?.let { TorrentLink.Magnet(magnetFor(it, title)) }
        ?: viaIndexer()
        ?: return null
    return TorrentResult(
        title = title,
        link = link,
        seeders = this["seeders"]?.jsonPrimitive?.intOrNull ?: 0,
        sizeBytes = this["size"]?.jsonPrimitive?.longOrNull ?: 0,
        indexer = this["indexer"]?.jsonPrimitive?.contentOrNull,
    )
}

private fun JsonObject.infoHash(): String? =
    this["infoHash"]?.jsonPrimitive?.contentOrNull?.takeIf { HASH.matches(it) }
        ?: this["guid"]?.jsonPrimitive?.contentOrNull?.let { HASH_AT_END.find(it)?.groupValues?.get(1) }

private fun JsonObject.viaIndexer(): TorrentLink.ViaIndexer? {
    val url = this["downloadUrl"]?.jsonPrimitive?.contentOrNull ?: return null
    val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val path = parsed.rawPath?.takeIf { DOWNLOAD_PATH.matches(it) } ?: return null
    val query = parsed.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() && !it.startsWith("apikey=") }
    return TorrentLink.ViaIndexer(if (query.isEmpty()) path else path + "?" + query.joinToString("&"))
}

internal fun magnetFor(infoHash: String, title: String): String =
    "magnet:?xt=urn:btih:$infoHash&dn=" + java.net.URLEncoder.encode(title, Charsets.UTF_8).replace("+", "%20")

internal val HASH = Regex("[0-9A-Fa-f]{40}")
private val DOWNLOAD_PATH = Regex("""/\d+/download""")
private val HASH_AT_END = Regex("""/([0-9A-Fa-f]{40})/?(?:[?#].*)?$""")

/** Checked in order, but only a value starting `magnet:` is ever accepted. */
private val MAGNET_FIELDS = listOf("guid", "magnetUrl", "downloadUrl")

private val LENIENT = Json { ignoreUnknownKeys = true }
