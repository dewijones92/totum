package com.dewijones92.totum.data.update

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

public const val RELEASE_APK_NAME: String = "totum.apk"

public data class ReleaseApk(val url: HttpUrl, val sizeBytes: Long?)

public data class Release(
    val version: AppVersion,
    val tag: String,
    val page: HttpUrl,
    val publishedAt: String?,
    val apk: ReleaseApk? = null,
    val notes: String? = null,
)

public sealed interface ReleaseLookup {
    public data class Found(val release: Release) : ReleaseLookup
    public data class Unavailable(val detail: String) : ReleaseLookup
}

public fun interface ReleaseSource {
    public suspend fun latest(): ReleaseLookup

    public suspend fun byTag(tag: String): ReleaseLookup = ReleaseLookup.Unavailable("no lookup by tag")
}

public class GitHubLatestRelease(
    private val fetcher: HttpTextFetcher,
    private val repository: String = "dewijones92/totum",
) : ReleaseSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): ReleaseLookup = lookup("latest")

    override suspend fun byTag(tag: String): ReleaseLookup = lookup("tags/$tag")

    private suspend fun lookup(path: String): ReleaseLookup {
        val url = HttpUrl.of("https://api.github.com/repos/$repository/releases/$path")
        val body = when (val fetched = fetcher.fetch(url)) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure ->
                return ReleaseLookup.Unavailable("${fetched.detail} (http ${fetched.httpStatus ?: "none"})")
        }
        return parse(body)?.let { ReleaseLookup.Found(it) } ?: ReleaseLookup.Unavailable("unparseable reply")
    }

    private fun parse(body: String): Release? = runCatching {
        val release = json.parseToJsonElement(body).jsonObject
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = AppVersion.parse(tag) ?: return null
        val page = release["html_url"]?.jsonPrimitive?.contentOrNull?.let(HttpUrl::parse)
            ?: HttpUrl.of("https://github.com/$repository/releases/tag/$tag")
        Release(
            version = version,
            tag = tag,
            page = page,
            publishedAt = release["published_at"]?.jsonPrimitive?.contentOrNull,
            apk = apkOf(release),
            notes = release["body"]?.jsonPrimitive?.contentOrNull,
        )
    }.getOrNull()

    private fun apkOf(release: JsonObject): ReleaseApk? {
        val asset = release["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == RELEASE_APK_NAME } ?: return null
        val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull?.let(HttpUrl::parse) ?: return null
        return ReleaseApk(url, asset["size"]?.jsonPrimitive?.longOrNull)
    }
}
