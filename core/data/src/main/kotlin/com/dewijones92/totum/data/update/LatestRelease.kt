package com.dewijones92.totum.data.update

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class Release(val version: AppVersion, val tag: String, val page: HttpUrl, val publishedAt: String?)

public sealed interface ReleaseLookup {
    public data class Found(val release: Release) : ReleaseLookup
    public data class Unavailable(val detail: String) : ReleaseLookup
}

public fun interface ReleaseSource {
    public suspend fun latest(): ReleaseLookup
}

public class GitHubLatestRelease(
    private val fetcher: HttpTextFetcher,
    private val repository: String = "dewijones92/totum",
) : ReleaseSource {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun latest(): ReleaseLookup {
        val url = HttpUrl.of("https://api.github.com/repos/$repository/releases/latest")
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
        Release(version, tag, page, release["published_at"]?.jsonPrimitive?.contentOrNull)
    }.getOrNull()
}
