package com.dewijones92.totum.ui.subscriptions

import com.dewijones92.totum.common.Diag
import com.dewijones92.totum.data.podcast.PodcastRepository
import com.dewijones92.totum.data.podcast.SubscribeResult
import com.dewijones92.totum.domain.Following
import com.dewijones92.totum.domain.MediaItem
import com.dewijones92.totum.domain.MediaSource
import com.dewijones92.totum.domain.pillar
import kotlin.time.TimeSource

class SourceFollowing(
    private val setChannel: suspend (MediaSource.VideoChannel, Boolean) -> Boolean,
    private val podcasts: PodcastRepository,
    private val locate: suspend (MediaItem) -> MediaSource?,
) {
    suspend fun change(following: Following, subscribe: Boolean, from: String): Boolean = when (following) {
        is Following.Subscribed -> set(following.source, subscribe, from)
        is Following.NotSubscribed -> set(following.source, subscribe, from)
        is Following.NotSubscribedByName -> {
            val started = TimeSource.Monotonic.markNow()
            val located = locate(following.item)
            Diag.log(
                "follow",
                "looked up the channel of \"${following.item.title}\" (named \"${following.item.author}\") in " +
                    "${started.elapsedNow().inWholeMilliseconds}ms -> ${located?.id?.value ?: "nothing found"}",
            )
            located != null && set(located, subscribe, "$from, looked up by name")
        }
        is Following.Unknown -> {
            Diag.log("follow", "nothing to change from $from: ${following.because.phrase}")
            false
        }
    }

    suspend fun set(source: MediaSource, subscribe: Boolean, from: String): Boolean {
        val (ok, outcome) = when (source) {
            is MediaSource.VideoChannel -> setChannel(source, subscribe).let { ok ->
                ok to if (ok) "written to the YouTube account" else CHANNEL_NOT_WRITTEN
            }
            is MediaSource.PodcastFeed -> if (subscribe) {
                podcasts.subscribe(source.feedUrl).let { result ->
                    (result is SubscribeResult.Subscribed || result is SubscribeResult.AlreadySubscribed) to "$result"
                }
            } else {
                podcasts.unsubscribe(source.id)
                true to "removed from this device"
            }
        }
        val verb = if (subscribe) "subscribe to" else "unsubscribe from"
        Diag.log(
            "follow",
            "$verb \"${source.title}\" from $from [pillar=${source.pillar} id=${source.id.value}] -> " +
                "${if (ok) "done" else "FAILED"}: $outcome",
        )
        return ok
    }
}

private const val CHANNEL_NOT_WRITTEN = "not written: YouTube refused, or the channel has no id"
