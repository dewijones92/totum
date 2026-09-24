package com.dewijones92.totum.data.channel

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.xml.childElements
import com.dewijones92.totum.data.xml.firstChildElement
import com.dewijones92.totum.data.xml.firstChildText
import com.dewijones92.totum.data.xml.hardenedDocumentBuilderFactory
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaItemId
import com.dewijones92.totum.domain.SourceId
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime

public object ChannelFeedParser {

    public fun feedUrlFor(channelId: String): HttpUrl =
        HttpUrl.of("https://www.youtube.com/feeds/videos.xml?channel_id=$channelId")

    public fun uploads(xml: String): List<MediaItem>? {
        val root = runCatching {
            hardenedDocumentBuilderFactory().newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
                .documentElement
        }.getOrNull()?.takeIf { it.tagName == "feed" } ?: return null
        val channelTitle = root.firstChildText("title")
        return root.childElements("entry").mapNotNull { it.toItem(channelTitle) }
    }

    public fun latest(xml: String): MediaItem? = uploads(xml)?.maxByOrNull { it.publishedAt!! }

    private fun Element.toItem(channelTitle: String?): MediaItem? {
        val videoId = firstChildText("yt:videoId") ?: return null
        val channelId = firstChildText("yt:channelId") ?: return null
        val published = firstChildText("published")
            ?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
            ?: return null
        val channelUrl = HttpUrl.parse("https://www.youtube.com/channel/$channelId") ?: return null
        val watch = childElements("link").firstOrNull { it.getAttribute("rel") == "alternate" }
            ?.getAttribute("href")?.let(HttpUrl::parse)
            ?: HttpUrl.parse("https://www.youtube.com/watch?v=$videoId")
        return MediaItem(
            id = MediaItemId(videoId),
            sourceId = SourceId(channelUrl.value),
            title = firstChildText("title") ?: videoId,
            publishedAt = published,
            duration = null,
            author = firstChildElement("author")?.firstChildText("name") ?: channelTitle,
            thumbnailUrl = firstChildElement("media:group")?.firstChildElement("media:thumbnail")
                ?.getAttribute("url")?.let(HttpUrl::parse),
            mediaUrl = watch,
            sourceUrl = channelUrl,
        )
    }
}
