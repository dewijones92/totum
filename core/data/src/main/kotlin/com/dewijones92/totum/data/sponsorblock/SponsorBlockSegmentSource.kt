package com.dewijones92.totum.data.sponsorblock

import com.dewijones92.totum.common.HttpUrl
import com.dewijones92.totum.data.net.FetchResult
import com.dewijones92.totum.data.net.HttpTextFetcher
import com.dewijones92.totum.domain.SkipSegment
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import kotlin.time.Duration.Companion.seconds

/** Port for looking up skip-worthy segments of a video. */
public fun interface SkipSegmentSource {
    /** Segments for [videoId]; empty when there are none or lookup fails (fail-open enhancement). */
    public suspend fun segmentsFor(videoId: String): List<SkipSegment>
}

/**
 * SponsorBlock-backed [SkipSegmentSource] (community-crowdsourced segments,
 * keyless API). Any failure — network, 404 (no segments), unparseable body —
 * yields an empty list: skipping is an enhancement, never a blocker.
 */
public class SponsorBlockSegmentSource(
    private val fetcher: HttpTextFetcher,
    /** Read per request, so changing the setting takes effect on the next video. */
    private val categories: () -> Set<SkipCategory> = { DEFAULT_CATEGORIES },
) : SkipSegmentSource, FreshSkipSegments {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun segmentsFor(videoId: String): List<SkipSegment> =
        (lookup(videoId) as? SegmentLookup.Answered)?.segments.orEmpty()

    override suspend fun lookup(videoId: String): SegmentLookup {
        val encoded = URLEncoder.encode(videoId, Charsets.UTF_8)
        val enabled = categories()
        if (enabled.isEmpty()) return SegmentLookup.Answered(emptyList())
        val query = enabled.joinToString("&") { "category=${it.id}" }
        val url = HttpUrl.of("https://sponsor.ajay.app/api/skipSegments?videoID=$encoded&$query")

        val body = when (val fetched = fetcher.fetch(url)) {
            is FetchResult.Success -> fetched.body
            is FetchResult.Failure ->
                return if (fetched.httpStatus == HTTP_NOT_FOUND) {
                    SegmentLookup.Answered(emptyList())
                } else {
                    SegmentLookup.Unavailable(fetched.detail)
                }
        }

        return parse(body)?.let { SegmentLookup.Answered(it) } ?: SegmentLookup.Unavailable("unparseable reply")
    }

    private fun parse(body: String): List<SkipSegment>? =
        runCatching {
            json.parseToJsonElement(body).jsonArray.mapNotNull { element ->
                val pair = element.jsonObject["segment"]?.jsonArray ?: return@mapNotNull null
                val start = pair.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val end = pair.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                if (end > start && start >= 0) SkipSegment(start.seconds, end.seconds) else null
            }
        }.getOrNull()

    public companion object {
        private const val HTTP_NOT_FOUND = 404

        /**
         * On unless you say otherwise: the unambiguous "not the content" categories.
         * The rest exist but are opinionated — an intro or a recap is content to some
         * people — so they are offered rather than assumed.
         */
        public val DEFAULT_CATEGORIES: Set<SkipCategory> = setOf(
            SkipCategory.SPONSOR,
            SkipCategory.SELF_PROMO,
            SkipCategory.INTERACTION,
        )
    }
}
