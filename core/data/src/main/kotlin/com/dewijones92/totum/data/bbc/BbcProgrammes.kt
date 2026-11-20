package com.dewijones92.totum.data.bbc

import com.dewijones92.totum.common.HttpUrl
import java.net.URLEncoder

public object BbcProgrammes {
    private const val API = "https://ibl.api.bbci.co.uk/ibl/v1/"
    private const val PROGRAMMES = API + "programmes/"
    private const val EPISODES_PER_PAGE = 50
    private const val IMAGE_RECIPE = "640x360"
    private val PID = Regex("""[a-z0-9]{8}""")

    public fun feedUrl(pid: String): HttpUrl =
        HttpUrl.of("$PROGRAMMES$pid/episodes?rights=web&per_page=$EPISODES_PER_PAGE")

    public fun isProgramme(feedUrl: HttpUrl): Boolean = feedUrl.value.startsWith(PROGRAMMES)

    public fun pidOf(feedUrl: HttpUrl): String? =
        feedUrl.value.takeIf { isProgramme(feedUrl) }?.removePrefix(PROGRAMMES)?.substringBefore('/')
            ?.takeIf(PID::matches)

    public fun isBbcApi(url: HttpUrl): Boolean = url.value.startsWith(API)

    public fun searchUrl(query: String, limit: Int): HttpUrl = HttpUrl.of(
        "${API}new-search?q=${URLEncoder.encode(query, Charsets.UTF_8).replace("+", "%20")}&rights=web&per_page=$limit",
    )

    public fun programmePageUrl(pid: String): HttpUrl = HttpUrl.of("https://www.bbc.co.uk/iplayer/episodes/$pid")

    internal fun imageUrl(template: String?): HttpUrl? =
        template?.replace("{recipe}", IMAGE_RECIPE)?.let(HttpUrl::parse)
}
